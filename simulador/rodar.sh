#!/bin/sh
# Plano B sem Docker: broker Python (amqtt) + simulador, no mesmo computador.
# Uso: ./rodar.sh [usuario] [senha]   (padrão: supervilab / supervilab123)
set -e
cd "$(dirname "$0")"
U="${1:-supervilab}"; S="${2:-supervilab123}"
python3 -c "import paho.mqtt, amqtt" 2>/dev/null || pip install -r requirements.txt
python3 broker.py --port 1883 --user "$U" --senha "$S" &
BPID=$!
trap 'kill $BPID 2>/dev/null' EXIT INT TERM
sleep 1
python3 mqtt_sim.py --host 127.0.0.1 --port 1883 --user "$U" --senha "$S"
