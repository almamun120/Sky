"""Group management and per-member receipts."""
import asyncio, json, random, base64, websockets
HOST="ws://127.0.0.1:8080"
CT=base64.b64encode(b"\x33"+b"x"*50).decode()
async def jrx(ws,t=3): return json.loads(await asyncio.wait_for(ws.recv(),t))
async def until(ws,typ,t=3):
    while True:
        m=await jrx(ws,t)
        if m.get("type")==typ: return m
async def signup(phone,name):
    ws=await websockets.connect(HOST+"/ws")
    await ws.send(json.dumps({"type":"request_otp","phone":phone})); r=await jrx(ws)
    await ws.send(json.dumps({"type":"verify_otp","phone":phone,"code":r["dev_code"],"name":name}))
    await until(ws,"token"); await until(ws,"login_ok"); return ws
def J(ws,**k): return ws.send(json.dumps(k))
async def main():
    n=random.randint(10**7,9*10**7)
    P=[f"+8801{i}{n}" for i in range(5)]
    A,B,C,D,E=[await signup(p,nm) for p,nm in zip(P,["A","B","C","D","E"])]
    await J(A,type="create_group",name="T",members=[P[1],P[2]])
    g=await until(A,"group"); gid=g["gid"]; await until(B,"group"); await until(C,"group")
    # 1 only the owner may add; non-members/unknown refused
    await J(B,type="add_members",group=gid,members=[P[3]]); assert (await until(B,"error"))["code"]=="not_admin"
    await J(A,type="add_members",group=gid,members=["+8801999999999"]); assert (await until(A,"error"))["code"]=="unknown_member"
    await J(A,type="add_members",group=gid,members=[P[3]])
    for w in (A,B,C,D):
        g=await until(w,"group"); assert len(g["members"])==4 and g["gid"]==gid
    print("1 add_members ok (owner only, everyone sees new list)")
    # 2 per-member receipts reach the sender, one per member
    cts={p:{"t":3,"c":CT} for p in P[1:4]}
    await J(A,type="send_group",id="m1",group=gid,cts=cts); await until(A,"ack")
    for w in (B,C,D): assert (await until(w,"msg"))["id"]=="m1"
    for w in (B,C): await J(w,type="delivered",id="m1",**{"from":P[0],"group":gid})
    await J(B,type="read",id="m1",**{"from":P[0],"group":gid})
    seen=set()
    for _ in range(3):
        s=await until(A,"status"); assert s["group"]==gid and s["id"]=="m1"; seen.add((s["status"],s["by"]))
    assert seen=={("delivered",P[1]),("delivered",P[2]),("read",P[1])}, seen
    print("2 per-member receipts ok")
    # 3 remove a member: removed one told, others get new list, removed cannot send
    await J(A,type="remove_member",group=gid,phone=P[2])
    assert (await until(C,"group_removed"))["gid"]==gid
    for w in (A,B,D): g=await until(w,"group"); assert g["members"] and len(g["members"])==3
    await J(C,type="send_group",id="x",group=gid,cts={}); assert (await until(C,"error"))["code"]=="not_member"
    await J(B,type="remove_member",group=gid,phone=P[3]); assert (await until(B,"error"))["code"]=="not_admin"
    print("3 remove_member ok")
    # 4 removed person offline still learns about it; re-adding clears the notice
    await C.close(); await asyncio.sleep(0.3)
    await J(A,type="remove_member",group=gid,phone=P[3]); await until(D,"group_removed")
    C2=await websockets.connect(HOST+"/ws")
    r=None
    # log C back in with a fresh OTP login
    await J(C2,type="request_otp",phone=P[2]); r=await jrx(C2)
    await J(C2,type="verify_otp",phone=P[2],code=r["dev_code"],name="C"); await until(C2,"token")
    types=[]
    while True:
        try: types.append((await jrx(C2,1.0))["type"])
        except asyncio.TimeoutError: break
    assert "group_removed" in types, types
    await J(A,type="add_members",group=gid,members=[P[2]]); g=await until(C2,"group"); assert P[2] in [m["phone"] for m in g["members"]]
    print("4 offline removal notice ok")
    # 5 leave: owner leaves -> next member becomes owner
    await J(A,type="leave_group",group=gid); await until(A,"group_removed")
    while True:
        g=await until(B,"group")
        if P[0] not in [m["phone"] for m in g["members"]]: break
    assert g["owner"]!=P[0] and len(g["members"])==2, g
    # 6 down to one member -> group deleted
    owner=g["owner"]; ws={P[1]:B,P[2]:C2}
    await J(ws[owner],type="leave_group",group=gid)
    other=[p for p in ws if p!=owner][0]; await until(ws[other],"group_removed",5)
    await J(ws[other],type="send_group",id="z",group=gid,cts={}); assert (await until(ws[other],"error"))["code"]=="unknown_group"
    print("5/6 leave, ownership transfer, empty group deleted ok"); print("GROUP TESTS PASSED")
asyncio.run(main())
