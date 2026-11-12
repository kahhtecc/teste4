# SuperviLab, mini-supervisório Android para automação

Supervisão e controle de uma planta com **3 válvulas esféricas, 1 bomba e 2 tanques/IBCs** via MQTT, com:

- **App Android** (Kotlin + Jetpack Compose, minSdk 26): painel com diagrama animado, detalhe por equipamento, alarmes, histórico em gráfico e ajustes de conexão (TLS, usuário e senha). Modo demo sem broker.
- **Simulador Python** da planta com falhas simuláveis (sensor travado, válvula sem resposta, perda de conexão), broker incluso.
- **Firmware ESP32** (PlatformIO) com fail-safe, esquema de ligação e lista de materiais.
- **Versão web** da simulação para abrir no celular (`web/index.html`).

> ⚠️ **Segurança.** O sistema real só deve ser usado em equipamento com proteção física independente do software: botão de emergência que corta válvulas e bomba, válvula de alívio/ladrão e proteção térmica do motor. O app e o ESP32 não substituem isso.

## Estrutura
```
PROTOCOLO.md      tópicos e payloads MQTT
simulador/        planta.py (física), mqtt_sim.py, broker.py, docker-compose.yml, tests/
android/          core/ (lógica pura e cliente MQTT próprio), app/ (Compose), testes-jvm/
firmware/         platformio.ini, src/, ESQUEMA.md, BOM.md, teste_nativo/
web/              simulação web (um único HTML)
.github/          workflow: testes, APK, firmware, emulador + capturas, Release
```

## Opção 1: GitHub (APK pronto)
Repositório: https://github.com/kahhtecc/teste4 (Releases: https://github.com/kahhtecc/teste4/releases)
1. Para um repositório novo: envie estes arquivos (`git push`) e aguarde o workflow **build**.
2. Em **Releases** aparece `build-N` com o APK, o `.bin` do ESP32 e as capturas do emulador.
3. No celular: baixe o APK, permita "fontes desconhecidas" e instale. O app abre em **modo demo**.

## Opção 2: Android Studio
Abra a pasta `android/` (Ladybug ou mais novo, JDK 17). Build › Build APK(s). O release é assinado com a chave de depuração.

## Opção 3: linha de comando
```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk
```

## Simulador
Com Docker:
```bash
cd simulador
docker compose up
```
Sem Docker:
```bash
cd simulador
pip install -r requirements.txt
./rodar.sh            # broker Python na porta 1883 + simulador (usuário supervilab / senha supervilab123)
```
Testes: `cd simulador && python -m pytest tests -q` (26 testes).

TLS: `./gerar_certs.sh <IP-do-PC>` gera `mosquitto/certs/`; habilite a porta 8883 em `mosquitto/mosquitto.conf` e cole o `ca.crt` no app (Ajustes › Certificado CA).

## Conectar o app ao simulador
Ajustes › desligue **Modo demo** › endereço = IP do computador na rede Wi‑Fi, porta 1883 (sem TLS) ou 8883 (TLS), usuário `supervilab`, senha `supervilab123` › **Salvar e conectar**. Em Ajustes › Simular falhas, acione as falhas e veja os alarmes.

## Testes do núcleo Kotlin (sem Android)
```bash
KOTLINC=/caminho/kotlinc android/testes-jvm/run.sh   # 27 testes: JSON, protocolo, store, demo e MQTT real com TLS
```

## Firmware
Veja `firmware/README.md`, `firmware/ESQUEMA.md` e `firmware/BOM.md`.

## Licenças
Código: MIT. Fontes Sora e Manrope: SIL Open Font License (`android/licencas/`).
