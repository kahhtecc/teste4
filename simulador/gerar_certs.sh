#!/bin/sh
# Gera CA + certificado do broker para TLS (autoassinado, uso local). Informe o IP/host do broker.
set -e
HOST="${1:-localhost}"
D="$(dirname "$0")/mosquitto/certs"; mkdir -p "$D"; cd "$D"
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout ca.key -out ca.crt -subj "/CN=SuperviLab CA"
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$HOST"
if echo "$HOST" | grep -Eq '^[0-9.]+$'; then
  if [ "$HOST" = "127.0.0.1" ]; then SAN="DNS:localhost,IP:127.0.0.1"; else SAN="DNS:localhost,IP:127.0.0.1,IP:$HOST"; fi
else
  SAN="DNS:$HOST,DNS:localhost,IP:127.0.0.1"
fi
printf "subjectAltName=%s\n" "$SAN" > san.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 3650 -extfile san.ext
chmod 644 server.key ca.crt server.crt
echo "Certificados em $D. Importe ca.crt no app (Configurações > Certificado CA) e habilite a porta 8883 em mosquitto.conf."
