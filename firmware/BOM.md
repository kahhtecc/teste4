# Lista de materiais (BOM), com alternativas comuns no Brasil

Preços de referência em nov/2026 (Mercado Livre, Baú da Eletrônica, Usinainfo, Eletrogate); variam bastante.

| # | Item | Qtd | Sugestão | Alternativas | Preço aprox. |
|---|------|-----|----------|--------------|--------------|
| 1 | ESP32 DevKit v1 (30 pinos) | 1 | ESP32-WROOM-32 | ESP32-S3 DevKitC (ajustar pinos), NodeMCU-32S | R$ 35–60 |
| 2 | Módulo relé 4 canais 5 V optoacoplado | 1 | Songle SRD-05VDC | 2× módulo 2 canais; relé de estado sólido 5 V para as válvulas | R$ 20–35 |
| 3 | Válvula esférica motorizada 1/2" ou 3/4" CR-02 (2 fios, NC, 12 V) | 3 | Latão, 12 V DC | CR-01 (2 fios, memória, exige inversão), CR-05 (5 fios com feedback: permite estado real); versão 220 V AC acionada pelo relé | R$ 90–160 cada |
| 4 | Bomba d'água | 1 | Bomba periférica 1/2 CV 220 V | Bomba 12 V diafragma 60 L/min para bancada de testes (sem contator, direto no relé de 10 A) | R$ 250–400 |
| 5 | Contator 9 A 220 V bobina 220 V + relé térmico | 1 | WEG CWC09 + RW17 | Schneider LC1K09, Siemens 3RT | R$ 120–200 |
| 6 | Sensor ultrassônico à prova d'água JSN-SR04T | 2 | JSN-SR04T-2.0 | A02YYUW (UART, melhor em zona cega), transdutor de pressão submersível 4–20 mA 0–1 m, sensor capacitivo sem contato XKC-Y25 (só alto/baixo) | R$ 35–60 cada |
| 7 | Fonte chaveada 12 V 5 A | 1 | Mean Well LRS-75-12 | fonte 12 V 5 A genérica em caixa plástica | R$ 60–120 |
| 8 | Regulador buck 12→5 V 3 A | 1 | LM2596 módulo | MP1584, fonte 5 V separada | R$ 10–20 |
| 9 | Botão de emergência tipo cogumelo NC com trava | 1 | Metaltex / WEG 22 mm | qualquer com contato NC 10 A | R$ 25–50 |
| 10 | Disjuntor bipolar 10 A + caixa IP65 + bornes + prensa-cabos | 1 | — | caixa de passagem plástica 20×20 cm | R$ 80–150 |
| 11 | Resistores 1 kΩ e 2 kΩ (divisores do ECHO), fusível 2 A, fios 1,5 mm² e 0,5 mm² | — | — | — | R$ 20 |
| 12 | Adaptadores/engates para IBC (saída 2" S60x6 → 3/4") | 2 | adaptador S60x6 | — | R$ 30–60 cada |
| 13 | IBC 1000 L (usado, lavado) | 2 | — | bombonas 200 L para bancada (ajuste `alturaTanqueCm`) | R$ 150–350 cada |

**Total estimado (sem IBC e sem bomba industrial):** R$ 900–1.400.

## Para bancada de testes barata
ESP32 + módulo de 4 relés + 3 válvulas 12 V 1/2" + bomba 12 V de diafragma + 2 JSN-SR04T + 2 baldes de 20 L: cerca de R$ 600, tudo em 12 V e sem contator. Ajuste `alturaTanqueCm` para a altura do balde e `volumeL` em `logica.h` (10 L por %, hoje fixo para IBC de 1000 L).
