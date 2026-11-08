"""Calls use only your own STUN/TURN server: the `ice` list in login_ok never contains a Google address.
Run against a live server started with {expose_otp_in_reply, true}. With public_host/turn_urls and turn_secret set it also checks the TURN entry."""
import asyncio, json, random, websockets
HOST = "ws://127.0.0.1:4080"
async def jrx(ws, t=3): return json.loads(await asyncio.wait_for(ws.recv(), t))
async def until(ws, typ, t=3):
    while True:
        m = await jrx(ws, t)
        if m.get("type") == typ: return m
async def main():
    phone = f"+8801{random.randint(10**8, 9 * 10**8)}"
    ws = await websockets.connect(HOST + "/ws")
    await ws.send(json.dumps({"type": "request_otp", "phone": phone})); r = await jrx(ws)
    assert "dev_code" in r, "start the server with {expose_otp_in_reply, true} for the tests"
    await ws.send(json.dumps({"type": "verify_otp", "phone": phone, "code": r["dev_code"], "name": "Ice"}))
    ok = await until(ws, "login_ok")
    ice = ok.get("ice", [])
    text = json.dumps(ice).lower()
    assert "google" not in text and "19302" not in text, ice
    print("1 no Google address in ice ok:", ice)
    turn = [e for e in ice if "username" in e]
    if turn:
        assert any(u.startswith(("turn:", "turns:")) for u in turn[0]["urls"]) and turn[0]["credential"], turn
        print("2 TURN entry with time-limited credentials ok")
    else:
        print("2 no TURN configured (set public_host and turn_secret to test it)")
asyncio.run(main()); print("ALL OK")
