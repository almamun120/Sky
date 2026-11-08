#!/usr/bin/env bash
# Switch the OTP / two-step-PIN lines on the server console on or off while the server runs (no restart). Default: ON.
# Only the printing is switched. The SMS gateway (src/sky_sms.erl) is called either way.
#
#   ./scripts/console_secrets.sh status
#   ./scripts/console_secrets.sh on
#   ./scripts/console_secrets.sh off
#   ./scripts/console_secrets.sh config     # delete the switch: console_secrets of config/sys.config counts again
#
# The switch is a small file the server reads each time. The path must be the same as `console_flag_file`
# in config/sys.config (default /tmp/sky_console_secrets). Another path: SKY_CONSOLE_FLAG=/path ./scripts/console_secrets.sh off
set -euo pipefail
FLAG="${SKY_CONSOLE_FLAG:-/tmp/sky_console_secrets}"

state() {
  if [[ -f "$FLAG" ]]; then
    case "$(tr -d '[:space:]' < "$FLAG")" in
      on)  echo "console secrets: ON  ($FLAG)";;
      off) echo "console secrets: OFF ($FLAG)";;
      *)   echo "console secrets: $FLAG has no on/off in it; console_secrets of config/sys.config decides (default ON)";;
    esac
  else
    echo "console secrets: no switch file ($FLAG); console_secrets of config/sys.config decides (default ON)"
  fi
}

case "${1:-status}" in
  on)     echo on  > "$FLAG"; state;;
  off)    echo off > "$FLAG"; state;;
  config) rm -f "$FLAG"; state;;
  status) state;;
  *) echo "usage: $0 on|off|status|config" >&2; exit 2;;
esac
