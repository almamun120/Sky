#!/usr/bin/env bash
# A quick look at the running server (needs {metrics_token, ...} in config/sys.config).
#   SKY_METRICS_TOKEN=the-token ./scripts/health.sh [host:port]      (default 127.0.0.1:4080)
set -euo pipefail
HOST="${1:-127.0.0.1:4080}"
[[ -n "${SKY_METRICS_TOKEN:-}" ]] || { echo "set SKY_METRICS_TOKEN to the metrics_token of config/sys.config" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer $SKY_METRICS_TOKEN" "http://$HOST/metrics" \
  | grep -E '^(sky_connections|sky_online_users|sky_messages_total|sky_group_messages_total|sky_logins_total|sky_uptime_seconds|sky_console_secrets|sky_beam_run_queue|sky_beam_processes|sky_sms_gateway_up|sky_sms_sent_total|sky_rate_limited_total)'
