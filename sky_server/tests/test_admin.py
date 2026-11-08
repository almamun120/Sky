"""The admin web interface, from the outside (plain HTTP on port 8080, no extra packages). Run it ON THE SERVER MACHINE in the directory where the server
runs: it writes admin.json with a test password (the old file is put back at the end), logs in, reads the numbers, changes a setting,
manages a gateway and checks that the lock and the login rules work. Add --lock to also test the 15-minute lock (it locks 127.0.0.1 out of
the admin page for 15 minutes!)."""
import hashlib, http.cookiejar, json, os, sys, urllib.request, urllib.error
BASE = os.environ.get("SKY_ADMIN_HTTP", "http://127.0.0.1:8080")   # the admin interface is on 8080 (http) / 8443 (https), not on 4080
ADMIN = os.environ.get("SKY_ADMIN_FILE", "admin.json")
GW = os.environ.get("SKY_SMS_FILE", "sms_gateways.json")
PW = "test-admin-password-123"
jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def call(path, body=None, headers=True, cookies=True):
    h = {"X-Sky-Admin": "1"} if headers else {}
    data = None
    if body is not None: data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + "/admin/api/" + path, data=data, headers=h)
    try:
        r = (op if cookies else urllib.request.build_opener()).open(req); return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"{}")
        except ValueError: return e.code, {}
def main():
    old = open(ADMIN).read() if os.path.exists(ADMIN) else None
    oldgw = open(GW).read() if os.path.exists(GW) else None
    salt = os.urandom(16); h = hashlib.pbkdf2_hmac("sha256", PW.encode(), salt, 200000, 32)
    open(ADMIN, "w").write(json.dumps({"salt": salt.hex(), "iter": 200000, "hash": h.hex()}))
    try:
        # the page itself
        r = op.open(BASE + "/admin"); html = r.read().decode(); assert r.status == 200 and "Sky admin" in html and "default-src 'none'" in r.headers["content-security-policy"]
        print("1 page and security headers ok")
        # rules
        assert call("stats", headers=False)[0] == 400                 # without the X-Sky-Admin header
        assert call("stats")[0] == 401                                # not logged in
        assert call("login", {"password": "nope"})[0] == 401
        s, _ = call("login", {"password": PW}); assert s == 200
        assert any(c.name == "sky_admin" and c.has_nonstandard_attr("HttpOnly") for c in jar)
        print("2 login rules ok")
        # numbers
        s, st = call("stats"); assert s == 200 and {"connections", "online_users", "memory", "history", "gateways", "console_secrets"} <= set(st), st
        print("3 stats ok: %d connections, %d numbers online, console secrets %s" % (st["connections"], st["online_users"], st["console_secrets"]))
        # settings
        s, cur = call("settings"); assert s == 200 and "sms_text" in cur["settings"]
        assert call("settings", {"sms_text": "no placeholder"})[0] == 400
        assert call("settings", {"wire_formats": ["carrier-pigeon"]})[0] == 400
        assert call("settings", {"turn_urls": ["http://not-turn"]})[0] == 400
        assert call("settings", {"sms_text": "Sky code ~s"})[0] == 200
        assert call("settings")[1]["settings"]["sms_text"]["value"] == "Sky code ~s"
        assert call("settings", {"sms_text": cur["settings"]["sms_text"]["value"]})[0] == 200          # put it back
        print("4 settings ok")
        # gateways
        assert call("gateways", {"gateways": [{"id": "x", "url": "http://bad"}]})[0] == 400
        assert call("gateways", {"gateways": [{"id": "a", "url": "ws://127.0.0.1:1"}, {"id": "a", "url": "ws://127.0.0.1:2"}]})[0] == 400
        assert call("gateways", {"gateways": [{"id": "adm1", "name": "Admin test", "url": "ws://127.0.0.1:9", "prio": 5, "sim": 1}]})[0] == 200
        g = call("gateways")[1]["gateways"]; assert [x["id"] for x in g] == ["adm1"] and g[0]["sim"] == 1 and g[0]["enabled"] is True
        print("5 gateways ok")
        assert call("reports")[0] == 200
        assert call("logout", {})[0] == 200 and call("me")[0] == 401
        print("6 reports and logout ok")
        if "--lock" in sys.argv:
            for _ in range(5): call("login", {"password": "wrong"})
            assert call("login", {"password": PW})[0] == 429
            print("7 lock after 5 wrong passwords ok (this address is locked for 15 minutes)")
    finally:
        if old is None: os.remove(ADMIN)
        else: open(ADMIN, "w").write(old)
        if oldgw is None:
            if os.path.exists(GW): os.remove(GW)
        else: open(GW, "w").write(oldgw)
main(); print("ALL OK")
