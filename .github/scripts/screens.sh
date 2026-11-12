#!/bin/bash
# Instala o APK no emulador, abre cada tela em modo demo, tira capturas e checa se o app morreu.
APK="$1"; OUT="$2"; PKG=br.com.supervilab; ACT=$PKG/br.com.supervilab.app.MainActivity
mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 0 >/dev/null
adb shell settings put system screen_off_timeout 1800000 >/dev/null
adb install -r -g "$APK" || { echo "::error::falha ao instalar o APK"; exit 1; }

abrir() { # nome, extras...
  local nome="$1"; shift
  adb shell am force-stop $PKG
  adb shell am start -W -n $ACT --ez demo true "$@" >/dev/null
  sleep "${ESPERA:-4}"
  if ! adb shell pidof $PKG >/dev/null; then echo "::error::CRASH ao abrir $nome"; adb logcat -d -s AndroidRuntime:E | tail -40; echo "crash" > "$OUT/CRASH-$nome.txt"; return 1; fi
  adb exec-out screencap -p > "$OUT/$nome.png"
  echo "capturado $nome ($(stat -c %s "$OUT/$nome.png") bytes)"
}
# toca num elemento pelo content-desc (uiautomator), nunca por coordenadas fixas
tocar() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > /tmp/ui.xml
  local b; b=$(python3 - "$1" <<'PY'
import re,sys
d=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read(); alvo=sys.argv[1]
m=re.search(r'content-desc="'+re.escape(alvo)+r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', d) or re.search(r'text="'+re.escape(alvo)+r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', d)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")
PY
)
  if [ -z "$b" ]; then echo "::warning::não achei '$1' na tela"; return 1; fi
  adb shell input tap $b; sleep 1.2
}

abrir 01-painel --es screen painel
abrir 02-equipamentos --es screen equipamentos
abrir 03-detalhe-valvula --es screen equip:valvula/1
abrir 04-detalhe-bomba --es screen equip:bomba/1
abrir 05-detalhe-tanque --es screen equip:tanque/2
ESPERA=7 abrir 06-alarmes --es screen alarmes --es cenario alarmes
abrir 07-historico --es screen historico
abrir 08-ajustes --es screen ajustes

# interação real: abrir válvula 2 com confirmação, ver "Abrindo…" e o estado final
abrir 09-antes-comando --es screen equip:valvula/2
tocar "Abrir Válvula 2" && adb exec-out screencap -p > "$OUT/10-confirmacao.png"
tocar "Confirmar Abrir" && sleep 0.8 && adb exec-out screencap -p > "$OUT/11-abrindo.png"
sleep 3.5; adb exec-out screencap -p > "$OUT/12-aberta.png"
# painel depois do comando (fluxo animado) e aba alarmes pela barra inferior
tocar "Voltar"; sleep 1; adb exec-out screencap -p > "$OUT/13-equipamentos-apos.png"
tocar "Aba Painel"; sleep 1.5; adb exec-out screencap -p > "$OUT/14-painel-apos.png"
# simular queda de rede e ver o painel em DESCONHECIDO
tocar "Aba Ajustes"; sleep 1; tocar "Falha: Queda de rede 10 s"; sleep 0.5; tocar "Aba Painel"; sleep 1.5; adb exec-out screencap -p > "$OUT/15-painel-desconhecido.png"
adb logcat -d -s AndroidRuntime:E > "$OUT/logcat-erros.txt" || true
ls -la "$OUT"
