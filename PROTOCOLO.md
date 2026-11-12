# Protocolo MQTT — SuperviLab

Todos os tópicos começam com `planta/`. Payloads em JSON UTF-8. QoS 1. Tempos em milissegundos desde a época (epoch ms, UTC).
Estados são publicados **retidos** (retained) para que um cliente novo receba o último valor; comandos e acks **não** são retidos.

## Equipamentos
| id | tipo | descrição |
|----|------|-----------|
| `valvula/1` | válvula esférica | saída da bomba para o Tanque 2 |
| `valvula/2` | válvula esférica | dreno do Tanque 2 |
| `valvula/3` | válvula esférica | alimentação do Tanque 1 (rede) |
| `bomba/1` | bomba | transfere do Tanque 1 para o Tanque 2 (passa pela válvula 1) |
| `tanque/1` | IBC 1000 L | fonte |
| `tanque/2` | IBC 1000 L | destino |

## Tópicos

### Presença e vida
| tópico | retido | payload |
|--------|--------|---------|
| `planta/status` | sim | `{"online":true,"ts":...,"fw":"sim-1.0"}`. Last Will (LWT) do equipamento: `{"online":false}` |
| `planta/heartbeat` | não | `{"ts":...,"uptime_s":123,"modo":"normal"\|"seguro","seq":n}` a cada **2 s** |

Regra do app: sem heartbeat por **6 s** (ou broker desconectado) → todos os estados viram **DESCONHECIDO** e o alarme "Conexão perdida" é ativado. Nunca mostrar o último valor como atual.

### Válvulas (`n` = 1..3)
| tópico | retido | payload |
|--------|--------|---------|
| `planta/valvula/n/estado` | sim | `{"estado":"aberta"\|"fechada"\|"abrindo"\|"fechando"\|"falha","ts":...}` |
| `planta/valvula/n/cmd` | não | `{"cmd":"abrir"\|"fechar","id":"<uuid>","ts":...}` |
| `planta/valvula/n/ack` | não | `{"id":"<uuid>","ok":true\|false,"motivo":"...","ts":...}` |

Tempo de curso da válvula: ~3 s (`abrindo` → `aberta`). O ack é enviado ao **receber** o comando (confirma recepção, não conclusão). O app considera "válvula sem resposta" se não houver ack em **1,5 s** ou o estado final não chegar em **8 s**.

### Bomba
| tópico | retido | payload |
|--------|--------|---------|
| `planta/bomba/1/estado` | sim | `{"estado":"ligada"\|"desligada"\|"falha","ts":...}` |
| `planta/bomba/1/cmd` | não | `{"cmd":"ligar"\|"desligar","id":"<uuid>","ts":...}` |
| `planta/bomba/1/ack` | não | `{"id":"<uuid>","ok":true\|false,"motivo":"...","ts":...}` |

Intertravamento (no equipamento e no simulador): bomba **não liga** com a válvula 1 fechada (`ok:false, motivo:"valvula_1_fechada"`), e **desliga sozinha** se a válvula 1 fechar ou o Tanque 1 esvaziar (< 2 %).

### Tanques (`n` = 1..2)
| tópico | retido | payload |
|--------|--------|---------|
| `planta/tanque/n/nivel` | sim | `{"nivel_pct":45.2,"volume_l":452.0,"qualidade":"boa"\|"suspeita"\|"invalida","ts":...}` a cada **1 s** |

`qualidade`: `boa` = leitura normal; `suspeita` = sensor fora do esperado (ex.: sem variação com fluxo); `invalida` = sensor sem leitura (o equipamento entra em modo seguro).

Alarmes locais do app: nível **alto ≥ 90 %**, **baixo ≤ 10 %** (histerese 2 %).

### Simulação de falhas (apenas o simulador responde)
| tópico | payload |
|--------|---------|
| `planta/sim/falha` | `{"tipo":"sensor_travado"\|"valvula_sem_resposta"\|"perda_conexao","alvo":"tanque/1"\|"valvula/2"\|null,"ativa":true\|false,"duracao_s":10}` |
| `planta/sim/falhas` (retido) | `{"ativas":[...]}` |

## Modo seguro (firmware e simulador)
Sem comando/heartbeat do broker por **15 s**, ou sensor de nível inválido: **todas as válvulas fecham e a bomba desliga**; `modo:"seguro"` no heartbeat; `planta/status` continua `online:true`. Sai do modo seguro ao reconectar; os comandos anteriores **não** são refeitos.

## Exemplo de sequência
1. App publica `planta/valvula/1/cmd` `{"cmd":"abrir","id":"a1","ts":1700000000000}`
2. Equipamento responde `planta/valvula/1/ack` `{"id":"a1","ok":true,"ts":...}` e `planta/valvula/1/estado` `{"estado":"abrindo"}`
3. ~3 s depois: `planta/valvula/1/estado` `{"estado":"aberta"}`
4. App publica `planta/bomba/1/cmd` `{"cmd":"ligar","id":"b1"}` → ack ok → `estado:"ligada"`
5. `planta/tanque/1/nivel` cai 1 L/s; `planta/tanque/2/nivel` sobe 1 L/s.

## Segurança
- TLS (porta 8883) com usuário e senha; o app nunca guarda segredos no código, apenas nas preferências do usuário.
- O equipamento ignora comandos malformados (JSON inválido, `cmd` desconhecido) e responde `ok:false`.
