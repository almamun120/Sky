"""Call signalling with the SDP sealed inside the Signal session (default require_e2e=true)."""
import asyncio, json, random, base64, websockets
HOST="ws://127.0.0.1:8080"
b64=lambda b: base64.b64encode(b).decode()
async def jrx(ws,t=3): return json.loads(await asyncio.wait_for(ws.recv(),t))
async def until(ws,typ,t=3):
    while True:
        m=await jrx(ws,t)
        if m.get("type")==typ: return m
async def signup(phone,name):
    ws=await websockets.connect(HOST+"/ws")
    await ws.send(json.dumps({"type":"request_otp","phone":phone})); r=await jrx(ws)
    await ws.send(json.dumps({"type":"verify_otp","phone":phone,"code":r["dev_code"],"name":name}))
    t=await until(ws,"token"); await until(ws,"login_ok"); return ws,t["token"]
J=lambda ws,**k: ws.send(json.dumps(k))
async def main():
    n=random.randint(10**8,9*10**8); A=f"+88017{n}"; B=f"+88018{n}"
    a,ta=await signup(A,"Rahim"); b,tb=await signup(B,"Karim")
    big=b64(b"\x33"+b"s"*20000)          # a large (video) offer, ~27 KB base64
    await J(a,type="call",cid="c1",to=B,video=True,sdp="v=0 plain"); e=await until(a,"error"); assert e["code"]=="e2e_required" and e["cid"]=="c1"
    await J(a,type="call",cid="c1",to=B,video=True,sct="!!bad!!",sctype=3); e=await until(a,"error"); assert e["code"]=="bad_request" and e["cid"]=="c1"
    await J(a,type="call",cid="c1",to=B,video=True,sct=big,sctype=3); e=await until(a,"call_state"); assert e["state"]=="ringing"
    ci=await until(b,"call_in"); assert ci["sct"]==big and ci["sctype"]==3 and "sdp" not in ci and ci["video"] and ci["name"]=="Rahim"
    print("1 plaintext SDP refused, sealed offer relayed (large offer ok)")
    await J(b,type="call_answer",cid="c1",to=A,sdp="v=0 plain"); e=await until(b,"error"); assert e["code"]=="e2e_required" and e["cid"]=="c1"
    await J(b,type="call_answer",cid="c1",to=A,sct=b64(b"\x44"*300),sctype=2)
    an=await until(a,"call_answered"); assert an["sct"]==b64(b"\x44"*300) and an["sctype"]==2 and "sdp" not in an and an["from"]==B
    print("2 sealed answer relayed, plaintext answer refused")
    await J(a,type="call_ice",cid="c1",to=B,cand={"sdpMid":"0","sdpMLineIndex":0,"candidate":"c"}); assert (await until(b,"call_ice"))["cand"]["candidate"]=="c"
    await J(a,type="call_end",cid="c1",to=B,reason="hangup"); assert (await until(b,"call_end"))["reason"]=="hangup"
    print("3 ice and end ok")
    await b.close(); await asyncio.sleep(0.4)
    await J(a,type="call",cid="c2",to=B,video=False,sct=b64(b"\x33"*200),sctype=3); assert (await until(a,"call_state"))["state"]=="calling"
    await J(a,type="call_ice",cid="c2",to=B,cand={"sdpMid":"0","sdpMLineIndex":0,"candidate":"c2"})
    await asyncio.sleep(0.3)
    b=await websockets.connect(HOST+"/ws"); await J(b,type="auth",token=tb)
    ci=await until(b,"call_in"); assert ci["cid"]=="c2" and ci["sct"]==b64(b"\x33"*200)
    assert (await until(b,"call_ice"))["cid"]=="c2"
    print("4 offline ring keeps the sealed offer ok"); print("CALL E2E TESTS PASSED")
asyncio.run(main())
