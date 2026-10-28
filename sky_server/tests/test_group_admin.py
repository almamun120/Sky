"""Group admins, 'only admins can send', invite links, and the owner's successor."""
import asyncio, json, random, base64, websockets
HOST="ws://127.0.0.1:8080"
b64=lambda b: base64.b64encode(b).decode()
CT=b64(b"\x33"+b"x"*100)
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
async def err(ws,code): assert (await until(ws,"error"))["code"]==code
async def main():
    n=random.randint(10**7,9*10**7); P=[f"+8801{i}{n}" for i in range(6)]
    A,B,C,D,E,F=[await signup(p,nm) for p,nm in zip(P,"ABCDEF")]
    await J(A,type="create_group",name="T",members=[P[1],P[2]])
    g=await until(A,"group"); gid=g["gid"]
    assert g["admins"]==[P[0]] and g["announce"] is False and g["owner"]==P[0]
    for w in (B,C): await until(w,"group")
    print("1 new group: owner is the only admin ok")
    # 2 a plain member can do none of the admin things
    await J(B,type="add_members",group=gid,members=[P[3]]); await err(B,"not_admin")
    await J(B,type="set_admin",group=gid,phone=P[1],on=True); await err(B,"not_admin")
    await J(B,type="group_settings",group=gid,announce=True); await err(B,"not_admin")
    await J(B,type="invite_create",group=gid); await err(B,"not_admin")
    await J(B,type="remove_member",group=gid,phone=P[2]); await err(B,"not_admin")
    print("2 members refused ok")
    # 3 promote B; everybody sees the new list; the owner cannot be demoted
    await J(A,type="set_admin",group=gid,phone=P[1],on=True)
    for w in (A,B,C): assert sorted((await until(w,"group"))["admins"])==sorted([P[0],P[1]])
    await J(B,type="set_admin",group=gid,phone=P[0],on=False); await err(B,"bad_request")
    print("3 promote ok")
    # 4 admin B adds D and removes plain member C, but cannot remove the owner
    await J(B,type="add_members",group=gid,members=[P[3]]); assert len((await until(D,"group"))["members"])==4
    for w in (A,B,C): await until(w,"group")
    await J(B,type="remove_member",group=gid,phone=P[2]); await until(C,"group_removed")
    for w in (A,B,D): await until(w,"group")
    await J(B,type="remove_member",group=gid,phone=P[0]); await err(B,"not_admin")
    print("4 admin add/remove ok")
    # 5 only-admins mode: D is refused (visible error), admins still send
    await J(A,type="group_settings",group=gid,announce=True)
    for w in (A,B,D): assert (await until(w,"group"))["announce"] is True
    cts=lambda who: {p:{"t":3,"c":CT} for p in who}
    await J(D,type="send_group",id="g1",group=gid,cts=cts([P[0],P[1]]))
    e=await until(D,"error"); assert e["code"]=="admins_only" and e["id"]=="g1"
    await J(B,type="send_group",id="g2",group=gid,cts=cts([P[0],P[3]])); await until(B,"ack")
    assert (await until(D,"msg"))["id"]=="g2"
    await J(A,type="group_settings",group=gid,announce=False)
    for w in (A,B,D): await until(w,"group")
    await J(D,type="send_group",id="g3",group=gid,cts=cts([P[0],P[1]])); await until(D,"ack")
    print("5 only admins can send ok")
    # 6 invite: same code every time until reset; a member cannot create one; E joins; the old code dies
    await J(B,type="invite_create",group=gid); c1=(await until(B,"invite"))["code"]
    await J(A,type="invite_create",group=gid); assert (await until(A,"invite"))["code"]==c1
    await J(D,type="invite_create",group=gid); await err(D,"not_admin")
    await J(E,type="join_invite",code=c1.lower()); ge=await until(E,"group"); assert P[4] in [m["phone"] for m in ge["members"]]
    for w in (A,B,D): await until(w,"group")
    await J(B,type="invite_create",group=gid,reset=True); c2=(await until(B,"invite"))["code"]; assert c2!=c1
    await J(F,type="join_invite",code=c1); await err(F,"bad_invite")
    await J(F,type="join_invite",code="NOPE"); await err(F,"bad_invite")
    await J(F,type="join_invite",code=c2); await until(F,"group")
    print("6 invite links ok")
    # 7 the owner leaves: the other admin (B) takes over, ahead of the other members
    await J(A,type="leave_group",group=gid); await until(A,"group_removed")
    g=await until(B,"group"); assert g["owner"]==P[1] and P[0] not in g["admins"] and P[1] in g["admins"]
    print("7 successor ok")
asyncio.run(main()); print("ALL OK")
