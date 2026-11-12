// Lógica de controle pura (sem Arduino): intertravamentos, curso das válvulas, validação do sensor e fail-safe.
// Testada nativamente com g++ (teste_nativo/) e usada sem alteração no main.cpp.
#pragma once
#include <stdint.h>

namespace supervilab {

enum class EstadoValvula : uint8_t { FECHADA, ABERTA, ABRINDO, FECHANDO };
enum class Bomba : uint8_t { DESLIGADA, LIGADA };

inline const char* nome(EstadoValvula e) {
    switch (e) { case EstadoValvula::ABERTA: return "aberta"; case EstadoValvula::ABRINDO: return "abrindo"; case EstadoValvula::FECHANDO: return "fechando"; default: return "fechada"; }
}
inline const char* nome(Bomba b) { return b == Bomba::LIGADA ? "ligada" : "desligada"; }

struct Config {
    uint32_t cursoValvulaMs = 8000;      // CR-02 12 V leva ~5–10 s para abrir
    uint32_t timeoutBrokerMs = 15000;    // sem broker por 15 s → modo seguro
    uint32_t timeoutSensorMs = 5000;     // leituras inválidas por 5 s → modo seguro
    float nivelMinBombaPct = 2.0f;       // bomba não roda a seco
    float alturaTanqueCm = 100.0f;       // IBC: 100 cm de coluna útil
    float offsetSensorCm = 5.0f;         // distância do sensor até o nível 100 %
};

struct Valvula {
    EstadoValvula estado = EstadoValvula::FECHADA;
    EstadoValvula alvo = EstadoValvula::FECHADA;
    uint32_t inicioCurso = 0;
    bool rele = false;                   // true = bobina energizada = válvula aberta (CR-02 NC)
};

struct Tanque {
    float pct = -1.0f;                   // -1 = inválido
    float volumeL = -1.0f;
    bool valida = false;
    bool jaTeveLeitura = false;          // nunca leu nada válido desde o boot → modo seguro
    uint32_t ultimaValida = 0;
};

struct Resultado { bool ok; const char* motivo; };

class Logica {
public:
    Config cfg;
    Valvula valvulas[3];
    Bomba bomba = Bomba::DESLIGADA;
    Tanque tanques[2];
    bool modoSeguro = false;
    const char* motivoSeguro = "";

    // ---- comandos (índice 0..2 = válvula 1..3) ----
    Resultado comandarValvula(int i, const char* cmd, uint32_t agora) {
        if (i < 0 || i > 2) return {false, "equipamento_desconhecido"};
        if (modoSeguro) return {false, "modo_seguro"};
        bool abrir;
        if (igual(cmd, "abrir")) abrir = true; else if (igual(cmd, "fechar")) abrir = false; else return {false, "cmd_invalido"};
        Valvula& v = valvulas[i];
        EstadoValvula novo = abrir ? EstadoValvula::ABERTA : EstadoValvula::FECHADA;
        if (v.estado == novo && v.alvo == novo) return {true, "ja_estava"};
        v.alvo = novo; v.estado = abrir ? EstadoValvula::ABRINDO : EstadoValvula::FECHANDO; v.inicioCurso = agora; v.rele = abrir;
        return {true, ""};
    }

    Resultado comandarBomba(const char* cmd, uint32_t agora) {
        (void)agora;
        if (modoSeguro) return {false, "modo_seguro"};
        if (igual(cmd, "ligar")) {
            if (valvulas[0].estado != EstadoValvula::ABERTA) return {false, "valvula_1_fechada"};
            if (!tanques[0].valida || tanques[0].pct < cfg.nivelMinBombaPct) return {false, "tanque_1_vazio"};
            bomba = Bomba::LIGADA; return {true, ""};
        }
        if (igual(cmd, "desligar")) { bomba = Bomba::DESLIGADA; return {true, ""}; }
        return {false, "cmd_invalido"};
    }

    // ---- sensor: distância medida (cm) → nível. Retorna true se a leitura é plausível. ----
    bool atualizarNivel(int i, float distanciaCm, uint32_t agora) {
        Tanque& t = tanques[i];
        bool plausivel = distanciaCm > 1.0f && distanciaCm < (cfg.alturaTanqueCm + cfg.offsetSensorCm + 30.0f);
        if (!plausivel) { t.valida = false; return false; }
        float nivelCm = cfg.alturaTanqueCm - (distanciaCm - cfg.offsetSensorCm);
        if (nivelCm < 0) nivelCm = 0;
        if (nivelCm > cfg.alturaTanqueCm) nivelCm = cfg.alturaTanqueCm;
        t.pct = 100.0f * nivelCm / cfg.alturaTanqueCm;
        t.volumeL = 10.0f * t.pct;          // IBC de 1000 L
        t.valida = true; t.jaTeveLeitura = true; t.ultimaValida = agora;
        return true;
    }

    // ---- laço: chame a cada ~100 ms. Retorna máscara de mudanças: bit0..2 válvulas, bit3 bomba, bit4 modo ----
    uint8_t avancar(uint32_t agora, bool brokerConectado, uint32_t ultimoContatoBroker) {
        uint8_t mudou = 0;
        for (int i = 0; i < 3; i++) {
            Valvula& v = valvulas[i];
            if ((v.estado == EstadoValvula::ABRINDO || v.estado == EstadoValvula::FECHANDO) && agora - v.inicioCurso >= cfg.cursoValvulaMs) {
                v.estado = v.alvo; mudou |= (1 << i);
            }
        }
        // intertravamentos da bomba
        if (bomba == Bomba::LIGADA && (valvulas[0].estado != EstadoValvula::ABERTA || !tanques[0].valida || tanques[0].pct < cfg.nivelMinBombaPct)) {
            bomba = Bomba::DESLIGADA; mudou |= (1 << 3);
        }
        // fail-safe
        bool semBroker = !brokerConectado && (agora - ultimoContatoBroker > cfg.timeoutBrokerMs);
        bool sensorRuim = false;
        for (int i = 0; i < 2; i++) if (!tanques[i].valida && (!tanques[i].jaTeveLeitura || agora - tanques[i].ultimaValida > cfg.timeoutSensorMs)) sensorRuim = true;
        if ((semBroker || sensorRuim) && !modoSeguro) {
            entrarModoSeguro(semBroker ? "sem_conexao" : "sensor_invalido", agora); mudou |= 0x1F;
        } else if (modoSeguro && !semBroker && !sensorRuim && brokerConectado) {
            modoSeguro = false; motivoSeguro = ""; mudou |= (1 << 4);   // sai do modo seguro, sem refazer comandos
        }
        return mudou;
    }

    void entrarModoSeguro(const char* motivo, uint32_t agora) {
        modoSeguro = true; motivoSeguro = motivo; bomba = Bomba::DESLIGADA;
        for (auto& v : valvulas) {
            v.rele = false; v.alvo = EstadoValvula::FECHADA;
            if (v.estado != EstadoValvula::FECHADA) { v.estado = EstadoValvula::FECHANDO; v.inicioCurso = agora; }
        }
    }

    // saídas físicas
    bool releValvula(int i) const { return valvulas[i].rele; }
    bool releBomba() const { return bomba == Bomba::LIGADA; }

private:
    static bool igual(const char* a, const char* b) { if (!a || !b) return false; while (*a && *a == *b) { a++; b++; } return *a == *b; }
};

} // namespace supervilab
