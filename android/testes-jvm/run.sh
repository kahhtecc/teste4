#!/bin/bash
# Compila o núcleo + testes com kotlinc, sobe broker Python (auth + TLS) e simulador, roda os testes.
set -u
cd "$(dirname "$0")"
KOTLINC="${KOTLINC:-/home/user/ferramentas/kotlinc}"
COR="$KOTLINC/lib/kotlinx-coroutines-core-jvm.jar"
SCR="${TMPDIR:-/tmp}/supervilab-testes"; mkdir -p "$SCR" build
echo "== compilando =="
"$KOTLINC/bin/kotlinc" ../core/src/main/kotlin src -cp "$COR" -d build/testes.jar -nowarn 2>&1 | grep -v "JAVA_TOOL_OPTIONS" 
[ -s build/testes.jar ] || { echo "COMPILAÇÃO FALHOU"; exit 1; }
echo "== certificados =="
rm -rf "$SCR/certs"; mkdir -p "$SCR/certs"; cd "$SCR/certs"
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout ca.key -out ca.crt -subj "/CN=SuperviLab CA teste" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=127.0.0.1" 2>/dev/null
printf "subjectAltName=IP:127.0.0.1,DNS:localhost\n" > san.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 30 -extfile san.ext 2>/dev/null
cd - >/dev/null
SIM=../../simulador
P1=18831; P2=18832; P3=18833
echo "== broker e simulador =="
python3 $SIM/broker.py --bind 127.0.0.1 --port $P1 --user supervilab --senha segredo123 --tls --cert "$SCR/certs/server.crt" --chave "$SCR/certs/server.key" --porta-tls $P2 > "$SCR/broker.log" 2>&1 &
B1=$!
python3 $SIM/broker.py --bind 127.0.0.1 --port $P3 > "$SCR/broker_aux.log" 2>&1 &
B2=$!
sleep 1.5
python3 $SIM/mqtt_sim.py --host 127.0.0.1 --port $P1 --user supervilab --senha segredo123 > "$SCR/sim.log" 2>&1 &
S1=$!
rm -f /tmp/matar_broker_aux
( while [ ! -f /tmp/matar_broker_aux ]; do sleep 0.2; done; kill -9 $B2 2>/dev/null ) &
W=$!
trap 'kill $B1 $B2 $S1 $W 2>/dev/null' EXIT
sleep 1.5
echo "== testes =="
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Djava.io.tmpdir=/tmp -cp build/testes.jar:"$COR":"$KOTLINC/lib/kotlin-stdlib.jar" TestesKt $P1 $P2 $P3 "$SCR/certs/ca.crt" 2>&1 | grep -v JAVA_TOOL_OPTIONS
RC=${PIPESTATUS[0]}
exit $RC
