# Esquema de ligação, ESP32 + válvulas + bomba + nível

> ⚠️ **Segurança primeiro.** Este sistema só deve ser usado em equipamento com **proteção física independente do software**: botão de emergência que corta a alimentação das válvulas e da bomba, válvula de alívio/ladrão nos tanques e proteção térmica no motor. O ESP32 e o app são camadas de conveniência, não de segurança.

## Visão geral
```
 Rede ──[V3]──► Tanque 1 (IBC) ──► Bomba ──[V1]──► Tanque 2 (IBC) ──[V2]──► Dreno
              sensor nível 1 (topo)                 sensor nível 2 (topo)
```
- Válvulas: esféricas motorizadas **CR-02, 2 fios, normalmente fechadas** (abrem energizadas, fecham sozinhas sem energia). Isso dá o fail-safe por hardware: sem energia, tudo fecha.
- Bomba: acionada por **contator** (nunca direto no relé) com relé térmico.
- Nível: 2 sensores ultrassônicos à prova d'água **JSN-SR04T** no topo de cada IBC, apontados para a superfície do líquido.

## Pinos do ESP32 (DevKit v1, 30 pinos)
| Função | GPIO | Observação |
|--------|------|-----------|
| Relé válvula 1 (V1) | 26 | saída |
| Relé válvula 2 (V2) | 27 | saída |
| Relé válvula 3 (V3) | 14 | saída |
| Relé bomba (bobina do contator) | 12 | saída. GPIO12 é strapping: o módulo de relé "ativo em baixo" mantém LOW seguro no boot; se tiver problema de boot, troque por GPIO 13 e ajuste `PINO_RELE_BOMBA` |
| Trigger sensor 1 | 32 | saída 3,3 V (o JSN aceita) |
| Echo sensor 1 | 34 | **entrada apenas**, via divisor 5 V → 3,3 V |
| Trigger sensor 2 | 33 | saída |
| Echo sensor 2 | 35 | **entrada apenas**, via divisor |
| LED de estado | 2 | LED da placa: aceso = MQTT ok, piscando rápido = modo seguro |

Divisor de tensão para cada ECHO: `ECHO ── 1 kΩ ──┬── GPIO34/35` e `└── 2 kΩ ── GND` (5 V × 2/3 ≈ 3,3 V).

## Módulo de relés (4 canais, optoacoplado, 5 V)
```
ESP32 GPIO26 ─► IN1    ESP32 GPIO27 ─► IN2    ESP32 GPIO14 ─► IN3    ESP32 GPIO12 ─► IN4
ESP32 GND    ─► GND    Fonte 5 V     ─► VCC   (retire o jumper JD-VCC e alimente JD-VCC com os 5 V da fonte, GND comum)
```
Os módulos chineses comuns são **ativos em nível baixo**: `RELE_ATIVO_BAIXO = true` no código. Se o seu ligar em HIGH, troque para `false`.

### Saídas de potência
```
Fonte 12 V (+) ─► COM do relé 1..3        NO do relé N ─► fio vermelho da válvula N     fio preto da válvula ─► 12 V (−)
Fonte 5 V/12 V bobina do contator: COM do relé 4 ─► fase/positivo ; NO ─► A1 do contator ; A2 ─► neutro/negativo
Botão de emergência (NC, com trava) em SÉRIE com o positivo das válvulas E com a bobina do contator.
```
Bomba monofásica 220 V: contator 9–12 A + relé térmico ajustado à corrente nominal + disjuntor.

## Sensores JSN-SR04T
```
VCC ─► 5 V       GND ─► GND       TRIG ─► GPIO32 (ou 33)       ECHO ─► divisor ─► GPIO34 (ou 35)
```
- Monte no centro da tampa do IBC, perpendicular à superfície, a pelo menos 25 cm do nível máximo (zona cega ≈ 20–25 cm). Ajuste `alturaTanqueCm` (coluna útil) e `offsetSensorCm` (distância do sensor ao nível 100 %) em `logica.h` ou na `Config`.
- Vapor, espuma e paredes muito próximas atrapalham: use um tubo-guia de PVC 75 mm com furos se o líquido agitar.
- Alternativa mais robusta: transdutor de pressão submersível 4–20 mA (resistor 150 Ω + ADC do ESP32) ou sensor de pressão 0–5 V na saída inferior do IBC.

## Alimentação
- Fonte chaveada 12 V 5 A (válvulas consomem ~0,5 A cada durante o curso) + regulador buck 12→5 V 3 A para ESP32, relés e sensores.
- **GND comum** entre fonte, ESP32, módulo de relés e sensores. Fios de potência da bomba separados dos fios de sinal.
- Caixa plástica IP65, prensa-cabos, bornes. Fusível 2 A no 12 V das válvulas.

## Comportamento fail-safe (firmware)
| Situação | Ação |
|----------|------|
| Boot | todas as saídas desligadas antes de qualquer coisa |
| Sem broker por 15 s | fecha válvulas, desliga bomba, heartbeat `modo:"seguro"`, recusa comandos |
| Sensor sem leitura válida por 5 s (ou nunca desde o boot) | idem |
| Válvula 1 fecha ou Tanque 1 < 2 % | bomba desliga sozinha |
| Travamento do firmware | watchdog de 10 s reinicia o ESP32 (saídas voltam desligadas) |
| Queda de energia | válvulas CR-02 fecham mecanicamente; contator desarma |

## Gravação
1. Instale o PlatformIO (VS Code ou `pip install platformio`).
2. Copie `include/config.h.example` para `include/config.h` e preencha Wi‑Fi, broker, usuário/senha e a CA (gerada por `simulador/gerar_certs.sh`).
3. `pio run -t upload && pio device monitor -b 115200`.
4. No app: Ajustes → mesmo broker, usuário e senha → Salvar e conectar.
