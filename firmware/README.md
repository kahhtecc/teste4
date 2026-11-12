# Firmware ESP32 (PlatformIO)

- `src/logica.h`: lógica de controle pura (intertravamentos, curso das válvulas, validação do sensor, fail-safe). Testada no PC: `cd teste_nativo && g++ -std=c++17 -I../src teste_logica.cpp -o teste && ./teste`.
- `src/main.cpp`: Wi‑Fi, MQTT (TLS com CA própria ou sem TLS), sensores JSN-SR04T, relés, watchdog.
- `include/config.h.example`: modelo de configuração. Copie para `config.h` (ignorado pelo git) e preencha.
- `ESQUEMA.md`: ligação elétrica e pinos. `BOM.md`: lista de materiais.

Compilar: `pio run`. Gravar: `pio run -t upload`. Monitor: `pio device monitor -b 115200`.
O GitHub Actions do projeto compila o firmware a cada push (job `firmware`) e publica o `.bin` na Release.
