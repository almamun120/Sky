#!/usr/bin/env bash
# Manage the SMS gateways of the server while it runs. The server looks at the file every 5 seconds, no restart.
#   ./scripts/sms_gateways.sh list
#   ./scripts/sms_gateways.sh add  ws://192.168.1.100:8000 [--name "Office phone"] [--sim 1] [--prio 10] [--insecure] [--extra '{"token":"abc"}']
#   ./scripts/sms_gateways.sh edit g1 [--url wss://new:8000] [--name N] [--sim 2|none] [--prio P] [--insecure|--secure] [--extra JSON]
#   ./scripts/sms_gateways.sh enable g1  |  disable g1  |  delete g1
# A gateway is any program that speaks the SMS-over-WebSocket protocol (see src/sky_sms_gw.erl), for example an Android phone running an
# "SMS WebSocket gateway" app: ws://PHONE_IP:PORT. Gateways with a smaller --prio are tried first; if one fails the next one is used.
# The file is sms_gateways.json in the directory where the server runs; another place: SKY_SMS_FILE=/path ./scripts/sms_gateways.sh ...
# (and  {sms_gateways_file, "/path"}  in config/sys.config). Needs python3.
set -euo pipefail
command -v python3 >/dev/null || { echo "python3 is needed" >&2; exit 1; }
export SKY_SMS_FILE="${SKY_SMS_FILE:-sms_gateways.json}"
exec python3 - "$@" <<'PY'
import json, os, re, sys, tempfile

path = os.environ["SKY_SMS_FILE"]
def load():
    if not os.path.exists(path): return []
    with open(path) as f:
        data = json.load(f)
    if not isinstance(data, list): sys.exit(f"{path} must hold a JSON list")
    return data
def save(items):
    d = os.path.dirname(os.path.abspath(path))
    fd, tmp = tempfile.mkstemp(dir=d, prefix=".sms_gw_"); os.close(fd)
    with open(tmp, "w") as f: json.dump(items, f, indent=2, ensure_ascii=False); f.write("\n")
    os.replace(tmp, path)           # the server never sees a half-written file
URL = re.compile(r"^wss?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/\S*)?$")
def die(msg): sys.exit("error: " + msg)

def parse_opts(args, allowed):
    o = {}; i = 0
    while i < len(args):
        a = args[i]
        if a in ("--insecure", "--secure"): o["insecure"] = (a == "--insecure"); i += 1; continue
        if a in allowed and i + 1 < len(args): o[a[2:]] = args[i + 1]; i += 2; continue
        die(f"unknown or incomplete option {a}")
    return o

def apply(g, o):
    if "url" in o:
        if not URL.match(o["url"]): die("the address must look like ws://192.168.1.100:8000 or wss://gateway.example.com/path")
        g["url"] = o["url"]
    if "name" in o: g["name"] = o["name"]
    if "sim" in o:
        if o["sim"] in ("none", "0", ""): g["sim"] = None
        elif o["sim"] in ("1", "2"): g["sim"] = int(o["sim"])
        else: die("--sim must be 1, 2 or none")
    if "prio" in o:
        try: g["prio"] = int(o["prio"])
        except ValueError: die("--prio must be a number")
    if "insecure" in o: g["insecure"] = o["insecure"]
    if "extra" in o:
        try: e = json.loads(o["extra"])
        except ValueError: die("--extra must be a JSON object, e.g. '{\"token\":\"abc\"}'")
        if not isinstance(e, dict): die("--extra must be a JSON object")
        g["extra"] = e

cmd = sys.argv[1] if len(sys.argv) > 1 else "list"
items = load()
find = lambda i: next((g for g in items if g["id"] == i), None)

if cmd == "list":
    if not items: print(f"no gateway ({path})"); sys.exit(0)
    print(f"{'id':<6}{'state':<10}{'prio':<6}{'sim':<5}{'name':<20}url")
    for g in sorted(items, key=lambda g: (g.get("prio", 100), g["id"])):
        print(f"{g['id']:<6}{('on' if g.get('enabled', True) else 'DISABLED'):<10}{g.get('prio', 100):<6}{str(g.get('sim') or '-'):<5}"
              f"{str(g.get('name', ''))[:19]:<20}{g['url']}{'  (accepts self-signed certificates)' if g.get('insecure') else ''}")
elif cmd == "add":
    if len(sys.argv) < 3: die("add needs an address: add ws://192.168.1.100:8000")
    nums = [int(m.group(1)) for g in items if (m := re.fullmatch(r"g(\d+)", g["id"]))]
    g = {"id": f"g{max(nums, default=0) + 1}", "name": "", "url": "", "sim": None, "prio": 100, "enabled": True, "insecure": False, "extra": {}}
    apply(g, {"url": sys.argv[2], **parse_opts(sys.argv[3:], ("--name", "--sim", "--prio", "--extra"))})
    g["name"] = g["name"] or g["id"]
    items.append(g); save(items); print(f"added {g['id']}: {g['url']}")
elif cmd == "edit":
    g = find(sys.argv[2]) if len(sys.argv) > 2 else None
    if g is None: die("no gateway with that id (see: list)")
    apply(g, parse_opts(sys.argv[3:], ("--url", "--name", "--sim", "--prio", "--extra")))
    save(items); print(f"changed {g['id']}")
elif cmd in ("enable", "disable"):
    g = find(sys.argv[2]) if len(sys.argv) > 2 else None
    if g is None: die("no gateway with that id (see: list)")
    g["enabled"] = (cmd == "enable"); save(items); print(f"{cmd}d {g['id']}")
elif cmd == "delete":
    g = find(sys.argv[2]) if len(sys.argv) > 2 else None
    if g is None: die("no gateway with that id (see: list)")
    items.remove(g); save(items); print(f"deleted {g['id']}")
else:
    die("use: list | add | edit | enable | disable | delete")
PY
