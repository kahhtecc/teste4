// g++ -std=c++17 -I../src teste_logica.cpp -o teste && ./teste
#include "logica.h"
#include <cstdio>
#include <cstring>
using namespace supervilab;
static int passou = 0, falhou = 0;
#define VERIFICA(c) do { if (c) passou++; else { falhou++; printf("FALHOU linha %d: %s\n", __LINE__, #c); } } while (0)

static void sensoresOk(Logica& l, uint32_t t) { l.atualizarNivel(0, 35.0f, t); l.atualizarNivel(1, 85.0f, t); } // T1 70 %, T2 20 %

int main() {
    { // nível a partir da distância
        Logica l; VERIFICA(l.atualizarNivel(0, 5.0f, 1)); VERIFICA(l.tanques[0].pct == 100.0f);
        VERIFICA(l.atualizarNivel(0, 105.0f, 1)); VERIFICA(l.tanques[0].pct == 0.0f);
        VERIFICA(l.atualizarNivel(0, 35.0f, 1)); VERIFICA(l.tanques[0].pct > 69.9f && l.tanques[0].pct < 70.1f && l.tanques[0].volumeL > 699.0f);
        VERIFICA(!l.atualizarNivel(0, 0.0f, 1)); VERIFICA(!l.tanques[0].valida);      // eco perdido
        VERIFICA(!l.atualizarNivel(0, 400.0f, 1));                                      // fora do alcance
    }
    { // válvula: curso e relé
        Logica l; sensoresOk(l, 0);
        Resultado r = l.comandarValvula(0, "abrir", 1000); VERIFICA(r.ok); VERIFICA(l.valvulas[0].estado == EstadoValvula::ABRINDO); VERIFICA(l.releValvula(0));
        VERIFICA(l.avancar(5000, true, 5000) == 0); VERIFICA(l.valvulas[0].estado == EstadoValvula::ABRINDO);
        VERIFICA(l.avancar(9000, true, 9000) == 1); VERIFICA(l.valvulas[0].estado == EstadoValvula::ABERTA);
        r = l.comandarValvula(0, "abrir", 9100); VERIFICA(r.ok && strcmp(r.motivo, "ja_estava") == 0);
        r = l.comandarValvula(0, "explodir", 9100); VERIFICA(!r.ok && strcmp(r.motivo, "cmd_invalido") == 0);
        r = l.comandarValvula(7, "abrir", 9100); VERIFICA(!r.ok);
        r = l.comandarValvula(0, "fechar", 9200); VERIFICA(r.ok && !l.releValvula(0) && l.valvulas[0].estado == EstadoValvula::FECHANDO);
    }
    { // bomba: intertravamentos
        Logica l; sensoresOk(l, 0);
        Resultado r = l.comandarBomba("ligar", 0); VERIFICA(!r.ok && strcmp(r.motivo, "valvula_1_fechada") == 0);
        l.comandarValvula(0, "abrir", 0); l.avancar(8000, true, 8000);
        l.atualizarNivel(0, 104.0f, 8000); r = l.comandarBomba("ligar", 8000); VERIFICA(!r.ok && strcmp(r.motivo, "tanque_1_vazio") == 0);
        l.atualizarNivel(0, 35.0f, 8000); r = l.comandarBomba("ligar", 8000); VERIFICA(r.ok && l.releBomba());
        l.comandarValvula(0, "fechar", 8100); uint8_t m = l.avancar(8200, true, 8200); VERIFICA((m & 8) && l.bomba == Bomba::DESLIGADA); // desliga já no fechando
        l.comandarValvula(0, "abrir", 9000); l.avancar(17000, true, 17000); l.comandarBomba("ligar", 17000);
        l.atualizarNivel(0, 104.5f, 17100); m = l.avancar(17100, true, 17100); VERIFICA((m & 8) && !l.releBomba()); // tanque vazio desliga
        r = l.comandarBomba("x", 0); VERIFICA(!r.ok);
    }
    { // fail-safe: sem broker 15 s
        Logica l; sensoresOk(l, 0);
        l.comandarValvula(1, "abrir", 0); l.avancar(8000, true, 8000); l.comandarValvula(0, "abrir", 0); l.avancar(8001, true, 8001); l.comandarBomba("ligar", 8001);
        uint32_t t = 8001;
        for (int i = 0; i < 149; i++) { t += 100; sensoresOk(l, t); l.avancar(t, false, 8001); }
        VERIFICA(!l.modoSeguro);                                                        // 14,9 s ainda normal
        t += 200; sensoresOk(l, t); uint8_t m = l.avancar(t, false, 8001);
        VERIFICA(l.modoSeguro && (m & 0x10)); VERIFICA(strcmp(l.motivoSeguro, "sem_conexao") == 0);
        VERIFICA(!l.releBomba() && !l.releValvula(0) && !l.releValvula(1) && !l.releValvula(2));
        VERIFICA(l.valvulas[1].estado == EstadoValvula::FECHANDO);
        Resultado r = l.comandarValvula(2, "abrir", t); VERIFICA(!r.ok && strcmp(r.motivo, "modo_seguro") == 0);
        r = l.comandarBomba("ligar", t); VERIFICA(!r.ok);
        t += 8000; sensoresOk(l, t); l.avancar(t, false, 8001); VERIFICA(l.valvulas[1].estado == EstadoValvula::FECHADA);
        // broker volta → sai do modo seguro, tudo continua fechado/desligado
        t += 100; sensoresOk(l, t); m = l.avancar(t, true, t); VERIFICA(!l.modoSeguro && (m & 0x10));
        VERIFICA(l.bomba == Bomba::DESLIGADA && l.valvulas[0].estado == EstadoValvula::FECHADA);
        r = l.comandarValvula(2, "abrir", t); VERIFICA(r.ok);
    }
    { // fail-safe: sensor inválido 5 s; leitura válida de volta libera
        Logica l; sensoresOk(l, 0); l.comandarValvula(0, "abrir", 0); l.avancar(8000, true, 8000); sensoresOk(l, 8000); l.comandarBomba("ligar", 8000);
        uint32_t t = 8000;
        for (int i = 0; i < 49; i++) { t += 100; l.atualizarNivel(0, 35.0f, t); l.atualizarNivel(1, 0.0f, t); l.avancar(t, true, t); }
        VERIFICA(!l.modoSeguro);
        t += 200; l.atualizarNivel(1, 0.0f, t); l.avancar(t, true, t); VERIFICA(l.modoSeguro && strcmp(l.motivoSeguro, "sensor_invalido") == 0 && !l.releBomba());
        t += 100; l.atualizarNivel(1, 85.0f, t); l.avancar(t, true, t); VERIFICA(!l.modoSeguro);
    }
    { // sem nenhuma leitura válida desde o boot → modo seguro imediato (não aciona nada às cegas)
        Logica l; l.avancar(100, true, 100); VERIFICA(l.modoSeguro);
    }
    printf("RESULTADO firmware: %d verificações passaram, %d falharam\n", passou, falhou);
    return falhou ? 1 : 0;
}
