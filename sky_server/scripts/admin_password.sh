#!/usr/bin/env bash
# Set (or remove) the password of the admin web page (http://127.0.0.1:8080/admin or https://127.0.0.1:8443/admin). Without this file the page cannot be used.
#   ./scripts/admin_password.sh set        asks for a new password twice
#   ./scripts/admin_password.sh remove     switches the admin page off
# The file is admin.json in the directory where the server runs (SKY_ADMIN_FILE=/path or {admin_file, "/path"} in config/sys.config).
# Only a salted PBKDF2-SHA256 hash is stored. The server reads the file at every login, so no restart is needed. Needs python3.
set -euo pipefail
command -v python3 >/dev/null || { echo "python3 is needed" >&2; exit 1; }
export SKY_ADMIN_FILE="${SKY_ADMIN_FILE:-admin.json}"
case "${1:-}" in
  set)
    exec python3 - <<'PY'
import getpass, hashlib, json, os, secrets, sys
path = os.environ["SKY_ADMIN_FILE"]
a = getpass.getpass("new admin password (at least 12 characters): ")
if len(a) < 12: sys.exit("too short: use at least 12 characters (a sentence is fine)")
if a != getpass.getpass("again: "): sys.exit("the two passwords differ")
salt = secrets.token_bytes(16); it = 200000
h = hashlib.pbkdf2_hmac("sha256", a.encode(), salt, it, 32)
tmp = path + ".tmp"
fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f: json.dump({"salt": salt.hex(), "iter": it, "hash": h.hex()}, f)
os.replace(tmp, path)
print("saved", path)
PY
    ;;
  remove) rm -f "$SKY_ADMIN_FILE"; echo "admin page switched off ($SKY_ADMIN_FILE removed)";;
  *) echo "usage: $0 set|remove" >&2; exit 2;;
esac
