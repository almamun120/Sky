"""Two-step verification: with a PIN set, a new login needs it after the SMS code; 5 wrong tries lock the number."""
import asyncio, json, random, websockets
HOST = "ws://127.0.0.1:8080"
async def jrx(ws, t=3): return json.loads(await asyncio.wait_for(ws.recv(), t))
async def until(ws, typ, t=3, **m):
    while True:
        x = await jrx(ws, t)
        if x.get("type") == typ and all(x.get(k) == v for k, v in m.items()): return x
def J(ws, **k): return ws.send(json.dumps(k))
async def otp(phone, name, dev=True):
    ws = await websockets.connect(HOST + "/ws")
    await J(ws, type="request_otp", phone=phone); code = (await until(ws, "otp_sent"))["dev_code"]
    await J(ws, type="verify_otp", phone=phone, code=code, name=name); return ws
async def main():
    ph = f"+8801{random.randint(10**8, 9 * 10**8)}"
    A = await otp(ph, "Ana"); await until(A, "login_ok")
    assert (await until(A, "pin_state"))["on"] is False
    # 1 format and set
    await J(A, type="set_pin", pin="12ab56"); assert (await until(A, "error"))["code"] == "bad_pin_format"
    await J(A, type="set_pin", pin="12345"); assert (await until(A, "error"))["code"] == "bad_pin_format"
    await J(A, type="set_pin", pin="246810"); assert (await until(A, "pin_state"))["on"] is True
    print("1 set ok")
    # 2 changing needs the old PIN
    await J(A, type="set_pin", pin="111111", old="000000"); assert (await until(A, "error"))["code"] == "bad_pin"
    await J(A, type="set_pin", pin="135790", old="246810"); assert (await until(A, "pin_state"))["on"] is True
    print("2 change ok")
    # 3 a new login after the SMS code is asked for the PIN; wrong PIN refused; right PIN logs in
    B = await otp(ph, "Ana"); await until(B, "pin_needed")
    await J(B, type="verify_pin", pin="246810"); assert (await until(B, "error"))["code"] == "bad_pin"
    await J(B, type="verify_pin", pin="135790"); await until(B, "token"); await until(B, "login_ok")
    print("3 login with PIN ok")
    # 4 five wrong tries lock the number (also with the right PIN afterwards)
    C = await otp(ph, "Ana"); await until(C, "pin_needed")
    for _ in range(5):
        await J(C, type="verify_pin", pin="000000"); await until(C, "error")
    await J(C, type="verify_pin", pin="135790"); assert (await until(C, "error"))["code"] == "pin_locked"
    print("4 lock ok")
    # 5 removing needs the PIN
    await J(B, type="remove_pin", pin="999999"); assert (await until(B, "error"))["code"] in ("bad_pin", "pin_locked")
asyncio.run(main()); print("ALL OK (the lock stays for 15 minutes: use a new number to run this test again)")
