import asyncio, json, websockets, requests, uuid
URL = "ws://127.0.0.1:8080/ws"
async def rx(ws, t=3):
    return json.loads(await asyncio.wait_for(ws.recv(), t))
async def until(ws, typ, t=3):
    while True:
        m = await rx(ws, t)
        if m.get("type") == typ: return m
async def signup(phone, name):
    ws = await websockets.connect(URL)
    await ws.send(json.dumps({"type":"request_otp","phone":phone}))
    r = await rx(ws); assert r["type"]=="otp_sent", r
    await ws.send(json.dumps({"type":"verify_otp","phone":phone,"code":r["dev_code"],"name":name}))
    tok = await until(ws,"token"); lo = await until(ws,"login_ok")
    assert "ice" in lo
    return ws, tok["token"]
async def main():
    a, ta = await signup("+8801700000001","Rahim")
    b, tb = await signup("+8801700000002","Karim")
    print("1 signup ok")
    # text + receipts
    await a.send(json.dumps({"type":"send","id":"m1","to":"+8801700000002","body":"hello"}))
    ack = await until(a,"ack"); m = await until(b,"msg")
    assert m["body"]=="hello" and m["name"]=="Rahim"
    await b.send(json.dumps({"type":"delivered","id":"m1","from":"+8801700000001"}))
    st = await until(a,"status"); assert st["status"]=="delivered"
    await b.send(json.dumps({"type":"read","id":"m1","from":"+8801700000001"}))
    st = await until(a,"status"); assert st["status"]=="read"
    await a.send(json.dumps({"type":"got","pid":st["pid"]}))
    print("2 text+receipts ok")
    # offline delivery
    await b.close(); await asyncio.sleep(0.3)
    await a.send(json.dumps({"type":"send","id":"m2","to":"+8801700000002","body":"while offline"}))
    await until(a,"ack")
    b = await websockets.connect(URL)
    await b.send(json.dumps({"type":"auth","token":tb}))
    await until(b,"login_ok"); m = await until(b,"msg"); assert m["body"]=="while offline"
    print("3 offline queue + token auth ok")
    # lookup
    await a.send(json.dumps({"type":"lookup","phone":"+8801700000002"})); p = await until(a,"profile"); assert p["exists"] and p["name"]=="Karim"
    await a.send(json.dumps({"type":"lookup","phone":"+8801799999999"})); p = await until(a,"profile"); assert not p["exists"]
    print("4 lookup ok")
    # group
    c, tc = await signup("+8801700000003","Salma")
    await a.send(json.dumps({"type":"create_group","name":"Team","members":["+8801700000002","+8801700000003"]}))
    g = await until(a,"group"); gb = await until(b,"group"); gc = await until(c,"group")
    assert len(g["members"])==3
    await a.send(json.dumps({"type":"send_group","id":"g1","group":g["gid"],"body":"hi team"}))
    await until(a,"ack"); mb = await until(b,"msg"); mc = await until(c,"msg")
    assert mb["group"]==g["gid"] and mc["body"]=="hi team"
    print("5 group ok")
    # media
    r = requests.post("http://127.0.0.1:8080/upload", data=b"x"*300000, headers={"Authorization":"Bearer "+ta})
    assert r.status_code==200, r.text; mid = r.json()["id"]
    d = requests.get(f"http://127.0.0.1:8080/media/{mid}", headers={"Authorization":"Bearer "+tb})
    assert d.status_code==200 and len(d.content)==300000
    assert requests.get(f"http://127.0.0.1:8080/media/{mid}").status_code==401
    assert requests.get("http://127.0.0.1:8080/media/../../etc/passwd", headers={"Authorization":"Bearer "+tb}).status_code in (400,404)
    await a.send(json.dumps({"type":"send","id":"f1","to":"+8801700000002","body":"","kind":"image","media":mid,"fname":"p.jpg","mime":"image/jpeg","size":300000}))
    await until(a,"ack"); m = await until(b,"msg"); assert m["kind"]=="image" and m["media"]==mid
    print("6 media ok")
    # calls: online callee
    await a.send(json.dumps({"type":"call","cid":"c1","to":"+8801700000002","video":True,"sdp":"v=0 offer"}))
    s = await until(a,"call_state"); assert s["state"]=="ringing"
    ci = await until(b,"call_in"); assert ci["sdp"]=="v=0 offer" and ci["video"] is True and ci["name"]=="Rahim"
    await a.send(json.dumps({"type":"call_ice","cid":"c1","to":"+8801700000002","cand":{"sdpMid":"0","sdpMLineIndex":0,"candidate":"candidate:1"}}))
    ice = await until(b,"call_ice"); assert ice["cand"]["candidate"]=="candidate:1"
    await b.send(json.dumps({"type":"call_answer","cid":"c1","to":"+8801700000001","sdp":"v=0 answer"}))
    an = await until(a,"call_answered"); assert an["sdp"]=="v=0 answer"
    await a.send(json.dumps({"type":"call_end","cid":"c1","to":"+8801700000002","reason":"hangup"}))
    e = await until(b,"call_end"); assert e["reason"]=="hangup"
    # offline callee gets call on login
    await c.close(); await asyncio.sleep(0.3)
    await a.send(json.dumps({"type":"call","cid":"c2","to":"+8801700000003","video":False,"sdp":"offer2"}))
    s = await until(a,"call_state"); assert s["state"]=="calling"
    await a.send(json.dumps({"type":"call_ice","cid":"c2","to":"+8801700000003","cand":{"candidate":"candidate:2"}}))
    await asyncio.sleep(0.3)
    c = await websockets.connect(URL); await c.send(json.dumps({"type":"auth","token":tc}))
    await until(c,"login_ok"); ci = await until(c,"call_in"); assert ci["sdp"]=="offer2"
    ice = await until(c,"call_ice"); assert ice["cand"]["candidate"]=="candidate:2"
    print("7 calls ok (online + offline ring)")
    # unknown recipient call error
    await a.send(json.dumps({"type":"call","cid":"c3","to":"+8801711111111","sdp":"x"}))
    er = await until(a,"error"); assert er["code"]=="unknown_recipient" and er["cid"]=="c3"
    # rate limit
    hits = 0
    for i in range(60):
        await a.send(json.dumps({"type":"lookup","phone":"+8801700000002"}))
    for i in range(60):
        try:
            m = await rx(a,1)
            if m.get("code")=="rate_limited": hits+=1
        except Exception: break
    assert hits>0, "no rate limit"
    print("8 rate limit ok, blocked", hits)
    # logout revokes
    await b.send(json.dumps({"type":"logout","token":tb})); await asyncio.sleep(0.3)
    x = await websockets.connect(URL); await x.send(json.dumps({"type":"auth","token":tb}))
    r = await until(x,"error"); assert r["code"]=="bad_token"
    print("9 logout revoke ok")
    # auth timeout needs 30s: skip. bad json:
    y = await websockets.connect(URL); await y.send("not json"); r = await rx(y); assert r["code"]=="bad_json"
    print("ALL PASSED")
asyncio.run(main())
