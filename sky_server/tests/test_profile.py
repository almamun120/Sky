"""Display name and the encrypted profile blob (about + photo): stored opaque, withheld when blocked."""
import asyncio, json, random, base64, websockets
HOST="ws://127.0.0.1:8080"
b64=lambda b: base64.b64encode(b).decode()
async def jrx(ws,t=3): return json.loads(await asyncio.wait_for(ws.recv(),t))
async def until(ws,typ,t=3,**match):
    while True:
        m=await jrx(ws,t)
        if m.get("type")==typ and all(m.get(k)==v for k,v in match.items()): return m
async def signup(phone,name):
    ws=await websockets.connect(HOST+"/ws")
    await J(ws,type="request_otp",phone=phone); r=await jrx(ws)
    await J(ws,type="verify_otp",phone=phone,code=r["dev_code"],name=name); await until(ws,"login_ok"); return ws
def J(ws,**k): return ws.send(json.dumps(k))
async def main():
    n=random.randint(10**7,9*10**7); P=[f"+8801{i}{n}" for i in range(3)]
    A,B,C=[await signup(p,nm) for p,nm in zip(P,"ABC")]
    # 1 name change: trimmed, visible through lookup, empty/too long refused
    await J(A,type="set_name",name="  Ana Rahman "); assert (await until(A,"me"))["name"]=="Ana Rahman"
    await J(B,type="lookup",phone=P[0]); assert (await until(B,"profile"))["name"]=="Ana Rahman"
    await J(A,type="set_name",name="   "); assert (await until(A,"error"))["code"]=="bad_name"
    await J(A,type="set_name",name="x"*65); assert (await until(A,"error"))["code"]=="bad_request"
    print("1 name ok")
    # 2 nothing stored yet -> blob null
    await J(B,type="get_profile",phone=P[0]); r=await until(B,"profile_blob"); assert r["blob"] is None and r["phone"]==P[0]
    # 3 store and read back byte for byte; newest wins; bad base64 / too big refused
    blob=b64(b"\x01"*60); await J(A,type="set_profile",blob=blob); ts=(await until(A,"profile_saved"))["ts"]
    await J(B,type="get_profile",phone=P[0]); r=await until(B,"profile_blob"); assert r["blob"]==blob and r["ts"]==ts
    blob2=b64(b"\x02"*70); await J(A,type="set_profile",blob=blob2); await until(A,"profile_saved")
    await J(C,type="get_profile",phone=P[0]); assert (await until(C,"profile_blob"))["blob"]==blob2
    await J(A,type="set_profile",blob="!!bad!!"); assert (await until(A,"error"))["code"]=="bad_request"
    await J(A,type="set_profile",blob=b64(b"x"*7000)); assert (await until(A,"error"))["code"]=="bad_request"
    print("2-3 profile blob ok")
    # 4 blocked in either direction -> withheld; unblock -> back
    await J(A,type="block",phone=P[1],on=True); await until(A,"blocks")
    await J(B,type="get_profile",phone=P[0]); assert (await until(B,"profile_blob"))["blob"] is None
    await J(A,type="get_profile",phone=P[1]); assert (await until(A,"profile_blob"))["blob"] is None
    await J(A,type="block",phone=P[1],on=False); await until(A,"blocks")
    await J(B,type="get_profile",phone=P[0]); assert (await until(B,"profile_blob"))["blob"]==blob2
    print("4 blocked profile ok")
asyncio.run(main()); print("ALL OK")
