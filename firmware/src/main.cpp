// SuperviLab, firmware ESP32: 3 válvulas (relés), 1 bomba (relé/contator), 2 sensores de nível ultrassônicos.
// Protocolo MQTT: ver PROTOCOLO.md. Fail-safe: sem broker 15 s ou sensor inválido 5 s → tudo fechado/desligado.
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <esp_task_wdt.h>
#include "config.h"
#include "logica.h"

using namespace supervilab;

// ---------- pinos ----------
static const int PINO_RELE_V[3] = {26, 27, 14};   // válvulas 1..3 (CR-02 NC: relé fechado = aberta)
static const int PINO_RELE_BOMBA = 12;             // relé → contator da bomba
static const int PINO_TRIG[2] = {32, 33};          // JSN-SR04T tanque 1 e 2
static const int PINO_ECHO[2] = {34, 35};          // entradas apenas (34/35), via divisor 5 V → 3,3 V
static const int PINO_LED = 2;
static const bool RELE_ATIVO_BAIXO = true;         // módulos de relé comuns ligam com nível baixo

// ---------- estado ----------
Logica logica;
#if MQTT_TLS
WiFiClientSecure rede;
#else
WiFiClient rede;
#endif
PubSubClient mqtt(rede);
uint32_t ultimoContatoBroker = 0, proxNivel = 0, proxHeartbeat = 0, proxReconexao = 0, boot = 0;
uint32_t seq = 0;
char buf[256];

static void rele(int pino, bool ligado) { digitalWrite(pino, (ligado != RELE_ATIVO_BAIXO) ? HIGH : LOW); }
static void aplicarSaidas() {
    for (int i = 0; i < 3; i++) rele(PINO_RELE_V[i], logica.releValvula(i));
    rele(PINO_RELE_BOMBA, logica.releBomba());
}

// ---------- sensor: mediana de 5 leituras ----------
static float medirCm(int i) {
    float v[5]; int n = 0;
    for (int k = 0; k < 5; k++) {
        digitalWrite(PINO_TRIG[i], LOW); delayMicroseconds(4);
        digitalWrite(PINO_TRIG[i], HIGH); delayMicroseconds(12); digitalWrite(PINO_TRIG[i], LOW);
        unsigned long us = pulseIn(PINO_ECHO[i], HIGH, 30000UL);   // 30 ms ≈ 5 m
        if (us > 0) v[n++] = us / 58.0f;
        delay(25);
    }
    if (n < 3) return 0.0f;                                          // sem eco → inválido
    for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) if (v[b] < v[a]) { float t = v[a]; v[a] = v[b]; v[b] = t; }
    return v[n / 2];
}

// ---------- publicação ----------
static bool pub(const char* topico, JsonDocument& doc, bool retido) {
    if (!mqtt.connected()) return false;
    size_t n = serializeJson(doc, buf, sizeof(buf));
    return mqtt.publish(topico, (const uint8_t*)buf, n, retido);
}
static void pubValvula(int i) {
    JsonDocument d; d["estado"] = nome(logica.valvulas[i].estado); d["ts"] = (uint64_t)millis();
    char t[40]; snprintf(t, sizeof(t), "planta/valvula/%d/estado", i + 1); pub(t, d, true);
}
static void pubBomba() { JsonDocument d; d["estado"] = nome(logica.bomba); d["ts"] = (uint64_t)millis(); pub("planta/bomba/1/estado", d, true); }
static void pubNivel(int i) {
    JsonDocument d; const Tanque& t = logica.tanques[i];
    if (t.valida) { d["nivel_pct"] = roundf(t.pct * 10) / 10; d["volume_l"] = roundf(t.volumeL * 10) / 10; d["qualidade"] = "boa"; }
    else { d["nivel_pct"] = nullptr; d["volume_l"] = nullptr; d["qualidade"] = "invalida"; }
    d["ts"] = (uint64_t)millis();
    char tp[40]; snprintf(tp, sizeof(tp), "planta/tanque/%d/nivel", i + 1); pub(tp, d, true);
}
static void pubHeartbeat() {
    JsonDocument d; d["ts"] = (uint64_t)millis(); d["uptime_s"] = (millis() - boot) / 1000; d["modo"] = logica.modoSeguro ? "seguro" : "normal"; d["seq"] = ++seq;
    pub("planta/heartbeat", d, false);
}
static void pubTudo() {
    JsonDocument s; s["online"] = true; s["ts"] = (uint64_t)millis(); s["fw"] = "esp32-1.0"; pub("planta/status", s, true);
    for (int i = 0; i < 3; i++) pubValvula(i);
    pubBomba(); pubNivel(0); pubNivel(1); pubHeartbeat();
}
static void ack(const char* topicoCmd, const char* id, bool ok, const char* motivo) {
    JsonDocument d; if (id) d["id"] = id; else d["id"] = nullptr; d["ok"] = ok; d["motivo"] = motivo; d["ts"] = (uint64_t)millis();
    String t(topicoCmd); t.replace("/cmd", "/ack"); pub(t.c_str(), d, false);
}

// ---------- comandos ----------
static void aoMensagem(char* topico, byte* payload, unsigned int tam) {
    ultimoContatoBroker = millis();
    JsonDocument d;
    if (deserializeJson(d, payload, tam) || !d.is<JsonObject>()) { ack(topico, nullptr, false, "json_invalido"); return; }
    const char* cmd = d["cmd"] | ""; const char* id = d["id"] | (const char*)nullptr;
    int n = 0; char tipo[16] = {0};
    if (sscanf(topico, "planta/%15[a-z]/%d/cmd", tipo, &n) != 2) { ack(topico, id, false, "equipamento_desconhecido"); return; }
    uint32_t agora = millis();
    if (strcmp(tipo, "valvula") == 0) {
        Resultado r = logica.comandarValvula(n - 1, cmd, agora);
        ack(topico, id, r.ok, r.motivo); if (r.ok) { aplicarSaidas(); pubValvula(n - 1); }
    } else if (strcmp(tipo, "bomba") == 0 && n == 1) {
        Resultado r = logica.comandarBomba(cmd, agora);
        ack(topico, id, r.ok, r.motivo); if (r.ok) { aplicarSaidas(); pubBomba(); }
    } else ack(topico, id, false, "equipamento_desconhecido");
}

static void conectarMqtt() {
    if (mqtt.connected() || millis() < proxReconexao) return;
    proxReconexao = millis() + 3000;
    Serial.printf("MQTT: conectando a %s:%d\n", MQTT_HOST, MQTT_PORTA);
    if (mqtt.connect(MQTT_CLIENTE_ID, MQTT_USUARIO, MQTT_SENHA, "planta/status", 1, true, "{\"online\":false}")) {
        Serial.println("MQTT: conectado");
        mqtt.subscribe("planta/+/+/cmd", 1);
        ultimoContatoBroker = millis();
        pubTudo();
    } else Serial.printf("MQTT: falhou, rc=%d\n", mqtt.state());
}

void setup() {
    Serial.begin(115200);
    boot = millis();
    for (int i = 0; i < 3; i++) { pinMode(PINO_RELE_V[i], OUTPUT); rele(PINO_RELE_V[i], false); }
    pinMode(PINO_RELE_BOMBA, OUTPUT); rele(PINO_RELE_BOMBA, false);          // tudo desligado ao ligar
    for (int i = 0; i < 2; i++) { pinMode(PINO_TRIG[i], OUTPUT); digitalWrite(PINO_TRIG[i], LOW); pinMode(PINO_ECHO[i], INPUT); }
    pinMode(PINO_LED, OUTPUT);
    esp_task_wdt_init(10, true); esp_task_wdt_add(NULL);                     // watchdog: travou 10 s → reinicia (saídas voltam desligadas)
    WiFi.mode(WIFI_STA); WiFi.setAutoReconnect(true); WiFi.begin(WIFI_SSID, WIFI_SENHA);
#if MQTT_TLS
    rede.setCACert(CA_PEM);
#endif
    mqtt.setServer(MQTT_HOST, MQTT_PORTA); mqtt.setCallback(aoMensagem); mqtt.setKeepAlive(10); mqtt.setBufferSize(512);
    ultimoContatoBroker = millis();
}

void loop() {
    esp_task_wdt_reset();
    uint32_t agora = millis();
    if (WiFi.status() == WL_CONNECTED) { conectarMqtt(); mqtt.loop(); }
    if (mqtt.connected()) ultimoContatoBroker = agora;                       // keep-alive do PubSubClient confirma o broker
    if (agora >= proxNivel) {
        proxNivel = agora + 1000;
        for (int i = 0; i < 2; i++) logica.atualizarNivel(i, medirCm(i), agora);
        pubNivel(0); pubNivel(1);
    }
    uint8_t m = logica.avancar(agora, mqtt.connected(), ultimoContatoBroker);
    if (m) {
        aplicarSaidas();
        for (int i = 0; i < 3; i++) if (m & (1 << i)) pubValvula(i);
        if (m & 8) pubBomba();
        if (m & 16) { Serial.printf("modo %s %s\n", logica.modoSeguro ? "SEGURO" : "normal", logica.motivoSeguro); pubHeartbeat(); }
    }
    if (agora >= proxHeartbeat) { proxHeartbeat = agora + 2000; pubHeartbeat(); }
    digitalWrite(PINO_LED, logica.modoSeguro ? ((agora / 250) % 2) : mqtt.connected());   // pisca rápido em modo seguro
    delay(20);
}
