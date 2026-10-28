"""Block and report: a blocked sender is acknowledged but never delivered; calls ring into the void;
typing and online status are hidden both ways; the block list survives reconnects; reports are stored."""
import asyncio, json, random, base64, websockets
HOST="ws://127.0.0.1:8080"
b64=lambda b: base64.b64encode(b).decode()
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
async def login(phone,name,token=None):
    ws=await websockets.connect(HOST+"/ws")
    if token:
        await J(ws,type="auth",token=token); await until(ws,"login_ok"); return ws,token
    await J(ws,type="request_otp",phone=phone); r=await jrx(ws)
    await J(ws,type="verify_otp",phone=phone,code=r["dev_code"],name=name)
    tok=(await until(ws,"token"))["token"]; await until(ws,"login_ok"); return ws,tok
def J(ws,**k): return ws.send(json.dumps(k))
CT=b64(b"\x33"+b"x"*100)
async def main():
    n=random.randint(10**7,9*10**7); P=[f"+8801{i}{n}" for i in range(3)]
    (A,ta),(B,tb),(C,tc)=[await login(p,nm) for p,nm in zip(P,"ABC")]
    # 1 before any block everything flows; the login already sent an (empty) block list
    await J(A,type="send",id="m1",to=P[1],ct=CT,ctype=3); await until(A,"ack"); m=await until(B,"msg"); assert m["id"]=="m1"
    print("1 normal delivery ok")
    # 2 B blocks A: the server answers with the full list; A is not told anything
    await J(B,type="block",phone=P[0],on=True); b=await until(B,"blocks"); assert b["phones"]==[P[0]]
    await J(A,type="send",id="m2",to=P[1],ct=CT,ctype=3); ack=await until(A,"ack"); assert ack["id"]=="m2" and ack["ts"]>0
    assert await quiet(B,"msg") and await quiet(A,"error") and await quiet(A,"status")
    print("2 blocked message is acked but not delivered ok")
    # 3 other people are unaffected, and the blocker can still write to the blocked person
    await J(C,type="send",id="m3",to=P[1],ct=CT,ctype=3); await until(C,"ack"); assert (await until(B,"msg"))["id"]=="m3"
    await J(B,type="send",id="m4",to=P[0],ct=CT,ctype=3); await until(B,"ack"); assert (await until(A,"msg"))["id"]=="m4"
    print("3 only A -> B is cut ok")
    # 4 typing is not relayed from A to B, but is from B to A
    await J(A,type="typing",to=P[1],on=True); assert await quiet(B,"typing")
    await J(B,type="typing",to=P[0],on=True); assert (await until(A,"typing"))["from"]==P[1]
    print("4 typing ok")
    # 5 call from A rings forever (A sees \"ringing\"), B sees nothing
    await J(A,type="call",cid="c1",to=P[1],video=False,sct=CT,sctype=3); s=await until(A,"call_state"); assert s["state"]=="ringing"
    assert await quiet(B,"call_in")
    await J(A,type="call_end",cid="c1",to=P[1],reason="timeout"); assert await quiet(B,"call_end")
    print("5 calls ok")
    # 6 presence is hidden both ways, also live
    await J(A,type="presence",phone=P[1]); assert (await until(A,"presence")).get("hidden") is True
    await J(B,type="presence",phone=P[0]); assert (await until(B,"presence")).get("hidden") is True
    await J(C,type="presence",phone=P[1]); assert (await until(C,"presence"))["online"] is True
    print("6 presence hidden ok")
    # 7 the list is stored on the server: a new connection of B gets it at login
    await B.close(); B=await websockets.connect(HOST+"/ws"); await J(B,type="auth",token=tb)
    b=await until(B,"blocks"); assert b["phones"]==[P[0]]
    print("7 block list kept across logins ok")
    # 8 validation
    await J(B,type="block",phone=P[1],on=True); assert (await until(B,"error"))["code"]=="bad_request"   # cannot block yourself
    await J(B,type="block",phone="+8801999999999",on=True); assert (await until(B,"error"))["code"]=="unknown_recipient"
    await J(B,type="block",phone="12",on=True); assert (await until(B,"error"))["code"]=="bad_phone"
    print("8 validation ok")
    # 9 unblock: delivery and presence come back; the dropped message m2 is gone for good
    await J(B,type="block",phone=P[0],on=False); b=await until(B,"blocks"); assert b["phones"]==[]
    await J(A,type="send",id="m5",to=P[1],ct=CT,ctype=3); await until(A,"ack"); assert (await until(B,"msg"))["id"]=="m5"
    await J(A,type="presence",phone=P[1]); assert (await until(A,"presence"))["online"] is True
    print("9 unblock ok")
    # 10 report: stored, at most 5 messages kept, unknown numbers refused
    msgs=[{"ts":1000+i,"b":"spam %d"%i} for i in range(8)]
    await J(B,type="report",phone=P[0],reason="spam",msgs=msgs); r=await until(B,"reported"); assert r["phone"]==P[0]
    await J(B,type="report",phone="+8801999999999",reason="x"); assert (await until(B,"error"))["code"]=="unknown_recipient"
    print("10 report ok (check on the server: sky_db:list_reports() shows 5 messages)")
asyncio.run(main()); print("ALL OK")
