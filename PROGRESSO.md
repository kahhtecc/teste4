# PROGRESSO — SuperviLab (mini-supervisório Android)

## Pedido original (resumo fiel)
App Android de supervisão/controle de automação (3 válvulas, 1 bomba, 2 tanques/IBCs), simulador Python com falhas simuláveis, firmware ESP32 fail-safe, APK compilado via GitHub Actions com capturas e Release. Entregas: APK + Release, zip com README PT-BR, simulador (docker compose/script), firmware + esquema, versão web, oferta de vídeo demo. Relatório final com aviso de segurança (proteção física independente).

## Plano e status
| # | Etapa | Status |
|---|-------|--------|
| 0 | Ambiente verificado (dl.google.com bloqueado → APK só no GitHub Actions; broker amqtt em Python) | concluída |
| 1 | Protocolo MQTT (PROTOCOLO.md) | concluída |
| 2 | Simulador Python (planta + falhas + broker) e testes | concluída (26 testes passam: `cd simulador && python3 -m pytest tests -q`) |
| 3 | App Android: lógica pura (core) + testes com kotlinc | concluída (27 testes JVM passam, 3 execuções estáveis: `android/testes-jvm/run.sh`) |
| 4 | App Android: UI Compose (tema, telas, demo) | escrita; falta compilar no GitHub Actions e revisar capturas |
| 5 | Firmware ESP32 + esquema + BOM | concluída (lógica pura: 41 verificações passam com g++; main.cpp compila só no GitHub Actions, PlatformIO bloqueado aqui) |
| 6 | Repositório GitHub + Actions (APK, emulador, capturas, Release) | concluída: repositório https://github.com/kahhtecc/teste4, Release build-3 com APK/firmware/capturas; build-4 revisada e entregue (capturas OK) |
| 7 | Versão web de simulação (artifact) | concluída: https://claude.ai/artifact/XMm8Yq5RmifX6JCrmf7eGu |
| 8 | Zip + README + verificação final + relatório | concluída (SuperviLab-projeto.zip, relatório entregue) |

## Próximo passo
Tudo concluído.

(Histórico) Etapa 6: repositório GitHub (verificar se kahhtecc/teste4, teste-2 ou teste está vazio; usar add_repo com push) + workflow .github/workflows/build.yml (APK, firmware PlatformIO, emulador com capturas, Release). Em paralelo etapa 7 (web) e 8 (zip/README). UI já escrita em android/app (MainActivity, SuperviLabApp, ui/*, ui/telas/*), não compilada aqui (SDK bloqueado).

Concluído na etapa 4: UI Compose em android/app (tema escuro próprio, fontes Sora/Manrope já em res/font, telas: Painel, Equipamentos/detalhe, Alarmes, Histórico, Ajustes; MainActivity com extras demo/screen). Depois: etapa 5 firmware, etapa 6 repo GitHub (precisa de repositório: ver kahhtecc/teste4 etc. via add_repo, confirmar vazio antes de usar).

Concluído na etapa 3: núcleo Kotlin puro em android/core (JSON próprio, cliente MQTT próprio 3.1.1 com TLS, PlantaStore, DemoSimulador) + testes com kotlinc em /home/user/supervilab/android/testes-jvm/run.sh. Decisões: zero dependências externas no core (Maven bloqueado aqui); demo = porta do planta.py em Kotlin.

## Arquivos
- /home/user/supervilab/ (raiz do projeto)
- android/core (Json, Protocolo, Mqtt, Conexao, DemoPlanta, PlantaStore, Config), android/testes-jvm (run.sh, src/Testes.kt, src/TestesMqtt.kt)
- PROTOCOLO.md, simulador/ (planta.py, mqtt_sim.py, broker.py, tests/, docker-compose.yml, Dockerfile, rodar.sh, gerar_certs.sh, mosquitto/)

## Aviso agendado (retomar-apos-limite)
trigger_id: trig_01XR8FG4q8eB5Q1FqrQ9t6Qa (anteriores já dispararam)
