#!/usr/bin/env bash
# Sets up your own STUN/TURN server (coturn) on this machine (Debian/Ubuntu) and prints the lines for config/sys.config.
#   sudo ./scripts/turn_setup.sh chat.mydomain.com [PUBLIC_IP]
# It installs coturn, writes /etc/turnserver.conf with a new random secret (an existing file is kept as .bak), enables and
# restarts the service. Run it again to change the domain; the secret is then renewed and must be copied to sys.config again.
# Needs the TLS certificate for turns: (port 5349) as files. Caddy keeps its certificates under
# /var/lib/caddy/.local/share/caddy/certificates/ ; copy or link them and set CERT/KEY below (or env CERT=… KEY=…).
set -euo pipefail
DOMAIN="${1:?usage: $0 chat.mydomain.com [PUBLIC_IP]}"
PUBIP="${2:-}"
CERT="${CERT:-}"; KEY="${KEY:-}"
[[ $EUID -eq 0 ]] || { echo "run as root (sudo)"; exit 1; }
apt-get update -y && apt-get install -y coturn openssl
SECRET="$(openssl rand -hex 32)"
[[ -f /etc/turnserver.conf ]] && cp /etc/turnserver.conf /etc/turnserver.conf.bak
{
  echo "listening-port=3478"
  echo "tls-listening-port=5349"
  echo "min-port=50000"
  echo "max-port=51000"
  echo "fingerprint"
  echo "use-auth-secret"
  echo "static-auth-secret=$SECRET"
  echo "realm=$DOMAIN"
  echo "total-quota=100"
  echo "no-multicast-peers"
  echo "no-cli"
  echo "denied-peer-ip=10.0.0.0-10.255.255.255"
  echo "denied-peer-ip=172.16.0.0-172.31.255.255"
  echo "denied-peer-ip=192.168.0.0-192.168.255.255"
  echo "denied-peer-ip=127.0.0.0-127.255.255.255"
  [[ -n "$PUBIP" ]] && echo "external-ip=$PUBIP"
  if [[ -n "$CERT" && -n "$KEY" ]]; then echo "cert=$CERT"; echo "pkey=$KEY"; else echo "# no-tls: set CERT and KEY to enable turns: on 5349"; fi
} > /etc/turnserver.conf
sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn 2>/dev/null || true
systemctl enable coturn >/dev/null 2>&1 || true
systemctl restart coturn
echo
echo "coturn is running. Open in the firewall: 3478 udp+tcp, 5349 tcp, 50000-51000 udp."
echo "Put this in config/sys.config (inside the {sky, [...]} list) and restart the Sky server:"
echo
echo "    , {public_host, <<\"$DOMAIN\">>}"
echo "    , {turn_secret, <<\"$SECRET\">>}"
echo
