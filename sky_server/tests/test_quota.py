"""Upload quota. Start the server with {upload_quota_day_mb, 1} in config/sys.config (and {expose_otp_in_reply, true}); then:
a person may upload 1 MB in 24 hours; the upload that would pass it, and every one after it, is refused with HTTP 429 quota_exceeded."""
import asyncio, json, random, requests, websockets
HOST = "127.0.0.1:4080"
async def jrx(ws, t=3): return json.loads(await asyncio.wait_for(ws.recv(), t))
async def until(ws, typ, t=3):
    while True:
        m = await jrx(ws, t)
        if m.get("type") == typ: return m
async def token():
    phone = f"+8801{random.randint(10**8, 9 * 10**8)}"
    ws = await websockets.connect("ws://" + HOST + "/ws")
    await ws.send(json.dumps({"type": "request_otp", "phone": phone})); r = await jrx(ws)
    assert "dev_code" in r, "start the server with {expose_otp_in_reply, true}"
    await ws.send(json.dumps({"type": "verify_otp", "phone": phone, "code": r["dev_code"], "name": "Q"}))
    return (await until(ws, "token"))["token"]
def up(tok, n): return requests.post(f"http://{HOST}/upload", data=b"x" * n, headers={"Authorization": "Bearer " + tok})
async def main():
    tok = await token()
    r = up(tok, 600_000); assert r.status_code == 200, r.text
    print("1 600 KB accepted ok")
    r = up(tok, 600_000); assert r.status_code == 429 and r.text == "quota_exceeded", (r.status_code, r.text)
    print("2 the upload that would pass 1 MB refused (429) ok")
    r = up(tok, 10); assert r.status_code == 200, r.text
    print("3 a small one that still fits accepted ok")
    other = await token(); r = up(other, 600_000); assert r.status_code == 200
    print("4 another person has their own quota ok")
asyncio.run(main()); print("ALL OK")
