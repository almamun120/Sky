#!/usr/bin/env bash
# Makes a self-signed certificate for the admin web interface on https port 8443 (admin_tls/cert.pem + admin_tls/key.pem, valid 825 days).
#   ./scripts/admin_cert.sh [HOST-OR-IP ...]      names the certificate is valid for (default: localhost 127.0.0.1)
# Run it in the directory where the server runs, then restart the server. Browsers warn about a self-signed certificate once;
# for a warning-free page set admin_tls_certfile / admin_tls_keyfile in config/sys.config to a real certificate (e.g. the one of your domain).
set -euo pipefail
DIR="${SKY_ADMIN_TLS_DIR:-admin_tls}"
NAMES=("$@"); [[ ${#NAMES[@]} -eq 0 ]] && NAMES=(localhost 127.0.0.1)
SAN=""
for n in "${NAMES[@]}"; do
  if [[ "$n" =~ ^[0-9.]+$ || "$n" == *:* ]]; then SAN+="IP:$n,"; else SAN+="DNS:$n,"; fi
done
mkdir -p "$DIR"; umask 077
openssl req -x509 -newkey rsa:3072 -nodes -days 825 -subj "/CN=${NAMES[0]}" -addext "subjectAltName=${SAN%,}" \
  -keyout "$DIR/key.pem" -out "$DIR/cert.pem" 2>/dev/null
chmod 600 "$DIR/key.pem"
echo "made $DIR/cert.pem and $DIR/key.pem for: ${NAMES[*]}"
echo "restart the server; then open https://${NAMES[0]}:8443/admin"
