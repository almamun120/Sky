"""Contact discovery: hashed numbers in, registered people out."""
import asyncio, hashlib, json, random, websockets
HOST = "ws://127.0.0.1:8080"
async def jrx(ws, t=3): return json.loads(await asyncio.wait_for(ws.recv(), t))
async def until(ws, typ, t=3, **m):
    while True:
        x = await jrx(ws, t)
        if x.get("type") == typ and all(x.get(k) == v for k, v in m.items()): return x
async def signup(phone, name):
    ws = await websockets.connect(HOST + "/ws")
    await J(ws, type="request_otp", phone=phone); r = await jrx(ws)
    await J(ws, type="verify_otp", phone=phone, code=r["dev_code"], name=name); await until(ws, "login_ok"); return ws
def J(ws, **k): return ws.send(json.dumps(k))
h = lambda p: hashlib.sha256(("sky-contact-v1:" + p).encode()).digest()[:8].hex()
async def main():
    n = random.randint(10**7, 9 * 10**7); P = [f"+8801{i}{n}" for i in range(3)]
    A, B, C = [await signup(p, nm) for p, nm in zip(P, ["Ana", "Bob", "Cy"])]
    # 1 registered people are found with their names; unknown hashes and my own number are not
    await J(A, type="find_contacts", hashes=[h(P[0]), h(P[1]), h(P[2]), h("+8801999999999"), "zz", 5])
    got = {m["phone"]: m["name"] for m in (await until(A, "contacts_found"))["matches"]}
    assert got == {P[1]: "Bob", P[2]: "Cy"}, got
    print("1 found ok")
    # 2 somebody who blocked me is not listed
    await J(C, type="block", phone=P[0], on=True); await until(C, "blocks")
    await J(A, type="find_contacts", hashes=[h(P[1]), h(P[2])])
    assert [m["phone"] for m in (await until(A, "contacts_found"))["matches"]] == [P[1]]
    print("2 blocked person hidden ok")
    # 3 empty list ok, more than 500 refused
    await J(A, type="find_contacts", hashes=[]); assert (await until(A, "contacts_found"))["matches"] == []
    await J(A, type="find_contacts", hashes=["0" * 16] * 501); assert (await until(A, "error"))["code"] == "bad_request"
    print("3 limits ok")
asyncio.run(main()); print("ALL OK")
