"""SMS gateways: the server sends the code through a gateway, fails over to the next one, and reports when none works.
Run it ON THE SERVER MACHINE, in the directory where the server runs (it edits sms_gateways.json: SKY_SMS_FILE if you moved it). It starts two pretend gateways on ports 18001 and 18002 and puts
everything back at the end. Add --slow to include the 15 s time-out case."""
import asyncio, json, os, random, re, sys, time, websockets
from fake_sms_gateway import FakeGateway
HOST = "ws://127.0.0.1:4080"
GW_FILE = os.environ.get("SKY_SMS_FILE", "sms_gateways.json")
async def jrx(ws, t=20): return json.loads(await asyncio.wait_for(ws.recv(), t))
def J(ws, **k): return ws.send(json.dumps(k))
def phone(): return f"+8801{random.randint(10**8, 9 * 10**8)}"
async def ask(ph):
    ws = await websockets.connect(HOST + "/ws"); await J(ws, type="request_otp", phone=ph); return ws, await jrx(ws)
def gw(i, port, prio): return {"id": f"t{i}", "name": f"test {i}", "url": f"ws://127.0.0.1:{port}", "sim": 1, "prio": prio, "enabled": True, "insecure": False, "extra": {}}
async def main():
    slow = "--slow" in sys.argv
    old = open(GW_FILE).read() if os.path.exists(GW_FILE) else None
    g1, g2 = await FakeGateway(18001).start(), await FakeGateway(18002).start()
    try:
        open(GW_FILE, "w").write(json.dumps([gw(1, 18001, 10), gw(2, 18002, 20)]))
        print("waiting for the server to pick up the gateway list (it looks every 5 s)..."); await asyncio.sleep(9)
        # 1 the code goes to the first gateway and works for a login
        ph = phone(); ws, r = await ask(ph); assert r["type"] == "otp_sent", r
        assert len(g1.received) == 1 and g1.received[0]["phone_number"] == ph and g1.received[0]["sim"] == 1 and not g2.received
        code = re.search(r"\b(\d{6})\b", g1.received[0]["message"]).group(1)
        await J(ws, type="verify_otp", phone=ph, code=code, name="T"); 
        while (await jrx(ws))["type"] != "login_ok": pass
        print("1 SMS through gateway 1, code works ok")
        # 2 gateway 1 says it failed -> gateway 2 sends it
        g1.mode = "fail"; ph = phone(); ws, r = await ask(ph); assert r["type"] == "otp_sent", r
        assert g2.received and g2.received[-1]["phone_number"] == ph
        print("2 failover to gateway 2 ok")
        # 3 both fail -> the app is told
        g2.mode = "fail"; ws, r = await ask(phone()); assert r == {"type": "error", "code": "sms_failed"}, r
        print("3 all gateways failed -> error sms_failed ok")
        # 4 one gateway never answers -> after 15 s the other one is used
        if slow:
            g1.mode = "silent"; g2.mode = "ok"; t = time.time(); ph = phone(); ws, r = await ask(ph)
            assert r["type"] == "otp_sent" and 14 < time.time() - t < 25 and g2.received[-1]["phone_number"] == ph, (r, time.time() - t)
            print("4 silent gateway: used the next one after 15 s ok")
        else: print("4 (time-out case skipped: add --slow)")
    finally:
        await g1.stop(); await g2.stop()
        if old is None: os.remove(GW_FILE)
        else: open(GW_FILE, "w").write(old)
asyncio.run(main()); print("ALL OK")
