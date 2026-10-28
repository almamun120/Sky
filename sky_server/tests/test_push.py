"""Own push service (/push). Run against a live server in dev_mode, require_e2e default."""
import asyncio, json, random, websockets, base64
HOST="ws://127.0.0.1:8080"
rx=lambda ws,t=3: asyncio.wait_for(ws.recv(),t)
async def jrx(ws,t=3): return json.loads(await rx(ws,t))
async def until(ws,typ,t=3):
    while True:
        m=await jrx(ws,t)
        if m.get("type")==typ: return m
async def silent(ws,t=1.0):
    try: await rx(ws,t)
    except asyncio.TimeoutError: return True
    return False
async def signup(phone,name):
    ws=await websockets.connect(HOST+"/ws")
    await ws.send(json.dumps({"type":"request_otp","phone":phone})); r=await jrx(ws)
    await ws.send(json.dumps({"type":"verify_otp","phone":phone,"code":r["dev_code"],"name":name}))
    tok=await until(ws,"token"); await until(ws,"login_ok"); return ws,tok["token"]
async def push_open(token):
    p=await websockets.connect(HOST+"/push"); await p.send(json.dumps({"type":"hello","token":token}))
    return p
CT=base64.b64encode(b"\x33"+b"x"*100).decode()
async def send(ws,to,i): 
    await ws.send(json.dumps({"type":"send","id":i,"to":to,"ct":CT,"ctype":3})); await until(ws,"ack")
async def main():
    n=random.randint(10**8,9*10**8); A=f"+88017{n}"; B=f"+88018{n}"
    a,ta=await signup(A,"Rahim"); b,tb=await signup(B,"Karim")
    # 1 bad token is refused
    p=await push_open("nope"); m=await jrx(p); assert m["code"]=="bad_token"
    try: await rx(p); assert False
    except websockets.ConnectionClosed: pass
    print("1 bad token refused ok")
    # 2 hello ok, ping/pong, nothing pending -> no wake
    await b.close(); await asyncio.sleep(0.4)
    pb=await push_open(tb); assert (await jrx(pb))["type"]=="hello_ok"
    await pb.send(json.dumps({"type":"ping"})); assert (await jrx(pb))["type"]=="pong"
    assert await silent(pb), "unexpected frame"
    print("2 hello/ping ok")
    # 3 message while chat connection is offline -> wake, no content
    await send(a,B,"m1"); w=await jrx(pb); assert w=={"type":"wake","why":"msg"}, w
    print("3 wake on offline message ok (no content in frame)")
    # 4 burst of messages -> throttled to one wake
    for i in range(3): await send(a,B,f"burst{i}")
    got=0
    while not await silent(pb,0.8): got+=1
    assert got<=1, got
    print("4 wake throttling ok")
    # 5 call wake bypasses throttle
    await a.send(json.dumps({"type":"call","cid":"c1","to":B,"video":False,"sct":CT,"sctype":3}))
    w=await jrx(pb); assert w=={"type":"wake","why":"call"}, w
    print("5 wake on call ok")
    # 6 reconnect with items waiting -> wake straight away; second channel replaces first
    pb2=await push_open(tb); assert (await jrx(pb2))["type"]=="hello_ok"
    assert (await jrx(pb2))["type"]=="wake"
    try:
        while True: await rx(pb,2)
    except websockets.ConnectionClosed as e: assert e.rcvd and e.rcvd.reason=="replaced", e
    print("6 catch-up wake on connect + old channel replaced ok")
    # 7 chat connection online -> no wake
    b=await websockets.connect(HOST+"/ws"); await b.send(json.dumps({"type":"auth","token":tb}))
    await until(b,"login_ok")
    while not await silent(b,0.5): pass
    while not await silent(pb2,0.5): pass
    await send(a,B,"live1"); assert (await until(b,"msg"))["id"]=="live1"
    assert await silent(pb2), "woke although online"
    print("7 no wake while online ok")
    # 8 logout closes push channel and token no longer works
    await b.send(json.dumps({"type":"logout","token":tb}))
    try:
        while True: await rx(pb2,3)
    except websockets.ConnectionClosed as e: assert e.rcvd.reason=="logout", e
    p=await push_open(tb); assert (await jrx(p))["code"]=="bad_token"
    print("8 logout closes push ok")
    # 9 oversized/garbage frame closes
    pa=await push_open(ta); await jrx(pa); await pa.send("not json")
    try:
        while True: await rx(pa,2)
    except websockets.ConnectionClosed: pass
    print("9 garbage closes ok"); print("PUSH TESTS PASSED")
asyncio.run(main())
