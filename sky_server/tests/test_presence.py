"""Typing indicator, online/last seen, watch updates and the reciprocal privacy switch."""
import asyncio, json, random, websockets
HOST="ws://127.0.0.1:8080"
async def jrx(ws,t=3): return json.loads(await asyncio.wait_for(ws.recv(),t))
async def until(ws,typ,t=3,**match):
    while True:
        m=await jrx(ws,t)
        if m.get("type")==typ and all(m.get(k)==v for k,v in match.items()): return m
async def quiet(ws,typ,t=0.7):
    try:
        while True:
            if (await jrx(ws,t)).get("type")==typ: return False
    except asyncio.TimeoutError: return True
async def signup(phone,name):
    ws=await websockets.connect(HOST+"/ws")
    await ws.send(json.dumps({"type":"request_otp","phone":phone})); r=await jrx(ws)
    await ws.send(json.dumps({"type":"verify_otp","phone":phone,"code":r["dev_code"],"name":name}))
    await until(ws,"token"); p=await until(ws,"privacy"); assert p["presence"] is True; return ws
def J(ws,**k): return ws.send(json.dumps(k))
async def main():
    n=random.randint(10**7,9*10**7); P=[f"+8801{i}{n}" for i in range(3)]
    A,B,C=[await signup(p,nm) for p,nm in zip(P,"ABC")]
    # 1 typing 1:1 reaches only the addressee
    await J(A,type="typing",to=P[1],on=True)
    t=await until(B,"typing"); assert t["from"]==P[0] and t["on"] is True and "group" not in t
    assert await quiet(C,"typing"); print("1 typing 1:1 ok")
    # 2 typing in a group reaches members except the sender; non-members cannot spoof
    await J(A,type="create_group",name="G",members=[P[1]]); gid=(await until(A,"group"))["gid"]; await until(B,"group")
    await J(A,type="typing",group=gid,on=True); t=await until(B,"typing"); assert t["group"]==gid
    assert await quiet(A,"typing") and await quiet(C,"typing")
    await J(C,type="typing",group=gid,on=True); assert await quiet(A,"typing") and await quiet(B,"typing")
    print("2 typing group ok")
    # 3 presence: online now, watch gets live updates, last seen after disconnect
    await J(A,type="presence",phone=P[1]); p=await until(A,"presence"); assert p["online"] is True
    await J(A,type="watch",phone=P[1]); p=await until(A,"presence"); assert p["online"] is True
    await B.close(); p=await until(A,"presence",phone=P[1]); assert p["online"] is False and p["last_seen"]>0
    B=await websockets.connect(HOST+"/ws")
    await J(B,type="request_otp",phone=P[1]); r=await jrx(B)
    await J(B,type="verify_otp",phone=P[1],code=r["dev_code"],name="B"); await until(B,"token")
    p=await until(A,"presence",phone=P[1]); assert p["online"] is True
    print("3 presence + watch ok")
    # 4 unwatch stops updates
    await J(A,type="unwatch"); await B.close()
    assert await quiet(A,"presence",1.0); print("4 unwatch ok")
    # 5 privacy is reciprocal: B hides -> A sees hidden, B sees A hidden too
    B=await websockets.connect(HOST+"/ws")
    await J(B,type="request_otp",phone=P[1]); r=await jrx(B)
    await J(B,type="verify_otp",phone=P[1],code=r["dev_code"],name="B"); await until(B,"token")
    await J(B,type="set_privacy",presence=False); assert (await until(B,"privacy"))["presence"] is False
    await J(A,type="presence",phone=P[1]); assert (await until(A,"presence")).get("hidden") is True
    await J(B,type="presence",phone=P[0]); assert (await until(B,"presence")).get("hidden") is True
    await J(B,type="set_privacy",presence=True); await until(B,"privacy")
    await J(A,type="presence",phone=P[1]); assert (await until(A,"presence"))["online"] is True
    await J(A,type="presence",phone="+8801999999999"); assert (await until(A,"presence")).get("hidden") is True
    print("5 privacy ok")
asyncio.run(main()); print("ALL OK")
