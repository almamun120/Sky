import asyncio, json, websockets, base64
URL="ws://127.0.0.1:8080/ws"
b64=lambda b: base64.b64encode(b).decode()
async def rx(ws,t=3): return json.loads(await asyncio.wait_for(ws.recv(),t))
async def until(ws,typ,t=3):
    while True:
        m=await rx(ws,t)
        if m.get("type")==typ: return m
async def signup(phone,name):
    ws=await websockets.connect(URL)
    await ws.send(json.dumps({"type":"request_otp","phone":phone})); r=await rx(ws)
    await ws.send(json.dumps({"type":"verify_otp","phone":phone,"code":r["dev_code"],"name":name}))
    tok=await until(ws,"token"); await until(ws,"login_ok"); return ws,tok["token"]
def bundle(n, count):
    return {"type":"upload_keys","identity":b64(b"I"*33),"reg":1234,
            "spk":{"id":1,"pub":b64(b"S"*33),"sig":b64(b"G"*64)},
            "kyber":{"id":2,"pub":b64(b"K"*1569),"sig":b64(b"H"*64)},
            "prekeys":[{"id":100+i,"pub":b64(bytes([i])*33)} for i in range(count)]}
async def main():
    a,ta=await signup("+8801700000001","Rahim"); b,tb=await signup("+8801700000002","Karim")
    await a.close(); a=await websockets.connect(URL); await a.send(json.dumps({"type":"auth","token":ta}))
    await until(a,"login_ok"); await until(a,"keys_needed"); print("1 keys_needed ok")
    await a.send(json.dumps(bundle(1,25))); await b.send(json.dumps(bundle(2,21)))
    await asyncio.sleep(0.3)
    await a.send(json.dumps({"type":"fetch_keys","phone":"+8801700000002"}))
    k=await until(a,"keys"); assert k["identity"]==b64(b"I"*33) and k["reg"]==1234 and k["prekey"]["id"]>=100 and k["kyber"]["id"]==2
    first=k["prekey"]["id"]
    await a.send(json.dumps({"type":"fetch_keys","phone":"+8801700000002"})); k2=await until(a,"keys"); assert k2["prekey"]["id"]!=first
    low=await until(b,"prekeys_low"); assert low["count"]<20
    print("2 fetch/consume one-time prekeys + prekeys_low ok")
    await a.send(json.dumps({"type":"fetch_keys","phone":"+8801799999999"})); e=await until(a,"error"); assert e["code"]=="no_keys"
    bad=bundle(1,1); bad["kyber"]["pub"]="!!notbase64!!"; await a.send(json.dumps(bad)); e=await until(a,"error"); assert e["code"]=="bad_keys"
    print("3 validation ok")
    await a.send(json.dumps({"type":"send","id":"p1","to":"+8801700000002","body":"hi"})); e=await until(a,"error"); assert e["code"]=="e2e_required" and e["id"]=="p1"
    ct=b64(b"\x33"+b"x"*200)
    await a.send(json.dumps({"type":"send","id":"e1","to":"+8801700000002","ct":ct,"ctype":3}))
    await until(a,"ack"); m=await until(b,"msg"); assert m["ct"]==ct and m["ctype"]==3 and "body" not in m and m["name"]=="Rahim"
    await a.send(json.dumps({"type":"send","id":"e2","to":"+8801700000002","ct":ct,"ctype":9})); e=await until(a,"error")
    print("4 plaintext refused, ciphertext relayed, bad ctype rejected ok")
    c,tc=await signup("+8801700000003","Salma")
    await a.send(json.dumps({"type":"create_group","name":"Team","members":["+8801700000002","+8801700000003"]}))
    g=await until(a,"group"); await until(b,"group"); await until(c,"group")
    await a.send(json.dumps({"type":"send_group","id":"g1","group":g["gid"],"cts":{"+8801700000002":{"t":3,"c":ct}}}))
    e=await until(a,"error"); assert e["code"]=="missing_ct" and e["members"]==["+8801700000003"]
    await a.send(json.dumps({"type":"send_group","id":"g2","group":g["gid"],"cts":{"+8801700000002":{"t":3,"c":ct},"+8801700000003":{"t":2,"c":b64(b"yy")}}}))
    await until(a,"ack"); mb=await until(b,"msg"); mc=await until(c,"msg")
    assert mb["ct"]==ct and mc["ct"]==b64(b"yy") and mc["ctype"]==2 and mb["group"]==g["gid"]
    print("5 group per-member ciphertext ok")
    await c.close(); await asyncio.sleep(0.3)
    await a.send(json.dumps({"type":"send","id":"e3","to":"+8801700000003","ct":ct,"ctype":2})); await until(a,"ack")
    c=await websockets.connect(URL); await c.send(json.dumps({"type":"auth","token":tc})); await until(c,"login_ok")
    ids=[]
    for _ in range(2): ids.append((await until(c,"msg"))["id"])
    assert set(ids)=={"g2","e3"}, ids
    print("6 offline ciphertext queue ok"); print("E2E SERVER TESTS PASSED")
asyncio.run(main())
