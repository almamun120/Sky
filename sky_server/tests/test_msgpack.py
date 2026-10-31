"""Wire format: JSON (text) and MessagePack (binary) clients on the same server, negotiation, and that the
binary fields really travel as bin. Needs a server with the default config: wire_formats [msgpack, json].
(Set SKY_WIRE=json when the server runs with {wire_formats, [json]}: only the JSON-only checks run.)"""
import asyncio, json, os, random, base64, websockets
import mp
HOST = "ws://127.0.0.1:8080"
JSON_ONLY = os.environ.get("SKY_WIRE") == "json"
BIN_KEYS = {"ct", "c", "sct", "blob", "identity", "pub", "sig"}
b64 = lambda b: base64.b64encode(b).decode()

class C:
    """A client in one wire mode. send() takes raw bytes for the binary fields; recv gives raw bytes for them."""
    def __init__(self, ws): self.ws = ws; self.binary = ws.subprotocol == "sky.msgpack"; self.last_raw = None
    def _out(self, o, key=None):
        if isinstance(o, dict): return {k: self._out(v, k) for k, v in o.items()}
        if isinstance(o, list): return [self._out(v, key) for v in o]
        if isinstance(o, bytes) and not self.binary: return b64(o)
        return o
    def _in(self, o, key=None):
        if isinstance(o, dict): return {k: self._in(v, k) for k, v in o.items()}
        if isinstance(o, list): return [self._in(v, key) for v in o]
        if isinstance(o, str) and key in BIN_KEYS and not self.binary:
            try: return base64.b64decode(o, validate=True)
            except Exception: return o
        return o
    async def send(self, **f):
        d = self._out(f)
        await self.ws.send(mp.pack(d) if self.binary else json.dumps(d))
    async def recv(self, t=3):
        raw = await asyncio.wait_for(self.ws.recv(), t); self.last_raw = raw
        if self.binary: assert isinstance(raw, bytes), "MessagePack connection got a text frame"
        else: assert isinstance(raw, str), "JSON connection got a binary frame"
        return self._in(mp.unpack(raw) if self.binary else json.loads(raw))
    async def until(self, typ, t=3, **m):
        while True:
            x = await self.recv(t)
            if x.get("type") == typ and all(x.get(k) == v for k, v in m.items()): return x
    async def quiet(self, typ, t=0.7):
        try:
            while True:
                if (await self.recv(t)).get("type") == typ: return False
        except asyncio.TimeoutError: return True

async def connect(mode):
    subs = {"msgpack": ["sky.msgpack", "sky.json"], "json": ["sky.json"], "legacy": None}[mode]
    return C(await websockets.connect(HOST + "/ws", subprotocols=subs))

async def signup(mode, phone, name):
    c = await connect(mode)
    await c.send(type="request_otp", phone=phone); code = (await c.until("otp_sent"))["dev_code"]
    await c.send(type="verify_otp", phone=phone, code=code, name=name); await c.until("login_ok")
    return c

def keys(tag):
    return dict(type="upload_keys", identity=bytes([tag]) * 33, reg=1234,
                spk={"id": 1, "pub": bytes([tag + 1]) * 33, "sig": bytes([tag + 2]) * 64},
                kyber={"id": 2, "pub": bytes([tag + 3]) * 1569, "sig": bytes([tag + 4]) * 64},
                prekeys=[{"id": i, "pub": bytes([tag + 5 + i]) * 33} for i in range(3)])

async def main():
    n = random.randint(10**7, 9 * 10**7); P = [f"+8801{i}{n}" for i in range(3)]
    # 1 negotiation
    c = await connect("msgpack"); assert c.ws.subprotocol == ("sky.json" if JSON_ONLY else "sky.msgpack"); await c.ws.close()
    c = await connect("json"); assert c.ws.subprotocol == "sky.json"; await c.ws.close()
    c = await connect("legacy"); assert c.ws.subprotocol is None and not c.binary; await c.ws.close()
    try:
        await websockets.connect(HOST + "/ws", subprotocols=["sky.nonsense"]); raise SystemExit("an unknown protocol was accepted")
    except websockets.exceptions.InvalidHandshake: pass
    print("1 negotiation ok")
    if JSON_ONLY:
        print("JSON-only server: MessagePack checks skipped"); return
    # 2 a text frame on a MessagePack connection closes it (1003); garbage gets an error, not a crash
    c = await connect("msgpack"); await c.ws.send('{"type":"auth"}')
    try: await asyncio.wait_for(c.ws.recv(), 3); raise SystemExit("not closed")
    except websockets.exceptions.ConnectionClosed as e:
        code = getattr(e, "code", None) or getattr(getattr(e, "rcvd", None), "code", None); assert code == 1003, code
    c = await connect("msgpack"); await c.ws.send(b"\xc1\xc1\xc1"); assert (await c.until("error"))["code"] == "bad_msgpack"
    await c.ws.send(mp.pack([1, 2, 3])); assert (await c.until("error"))["code"] == "bad_request"
    print("2 wrong frame type and garbage ok")
    # 3 three people, three modes, same server
    A = await signup("msgpack", P[0], "A"); B = await signup("json", P[1], "B"); L = await signup("legacy", P[2], "L")
    print("3 sign-up in msgpack, json and legacy (no protocol) ok")
    # 4 keys: bin on one side, base64 on the other, identical bytes
    ka = keys(10); await A.send(**ka); await B.send(**keys(50)); await asyncio.sleep(0.3)
    await B.send(type="fetch_keys", phone=P[0]); k = await B.until("keys")
    assert k["identity"] == ka["identity"] and k["spk"]["pub"] == ka["spk"]["pub"] and k["kyber"]["sig"] == ka["kyber"]["sig"]
    assert k["prekey"]["pub"] in [p["pub"] for p in ka["prekeys"]]
    await A.send(type="fetch_keys", phone=P[1]); k = await A.until("keys"); assert k["identity"] == bytes([50]) * 33 and isinstance(k["identity"], bytes)
    print("4 key bundles identical across formats ok")
    # 5 messages in all directions; the ciphertext arrives byte for byte
    for src, dst, sp, dp in ((A, B, P[0], P[1]), (B, A, P[1], P[0]), (A, L, P[0], P[2]), (L, A, P[2], P[0])):
        ct = os.urandom(100)
        await src.send(type="send", id="m" + os.urandom(3).hex(), to=dp, ct=ct, ctype=3); await src.until("ack")
        m = await dst.until("msg"); assert m["ct"] == ct and m["from"] == sp, (m, ct)
        if dst.binary: assert ct in dst.last_raw and b64(ct).encode() not in dst.last_raw      # sent as bin, not base64 text
    print("5 messages ok (A msgpack, B json, L legacy)")
    # 6 the binary frame is smaller than the JSON frame of the same message
    ct = os.urandom(300)
    await B.send(type="send", id="s1", to=P[0], ct=ct, ctype=3); await B.until("ack"); await A.until("msg"); small = len(A.last_raw)
    await B.send(type="send", id="s2", to=P[2], ct=ct, ctype=3); await B.until("ack"); await L.until("msg"); big = len(L.last_raw)
    assert small < big - 80, (small, big)
    print(f"6 same message: MessagePack {small} bytes, JSON {big} bytes ok")
    # 7 group message with a ciphertext per member
    await A.send(type="create_group", name="G", members=[P[1], P[2]]); g = await A.until("group"); gid = g["gid"]
    await B.until("group"); await L.until("group")
    cts = {P[1]: {"t": 3, "c": os.urandom(80)}, P[2]: {"t": 3, "c": os.urandom(80)}}
    await A.send(type="send_group", id="g1", group=gid, cts=cts); await A.until("ack")
    assert (await B.until("msg"))["ct"] == cts[P[1]]["c"] and (await L.until("msg"))["ct"] == cts[P[2]]["c"]
    print("7 group ciphertexts ok")
    # 8 profile blob, null stays null; a call SDP
    blob = os.urandom(70); await A.send(type="set_profile", blob=blob); await A.until("profile_saved")
    await B.send(type="get_profile", phone=P[0]); assert (await B.until("profile_blob"))["blob"] == blob
    await L.send(type="get_profile", phone=P[1]); assert (await L.until("profile_blob"))["blob"] is None
    await B.send(type="call", cid="c1", to=P[0], video=False, sct=os.urandom(200), sctype=3)
    ci = await A.until("call_in"); assert isinstance(ci["sct"], bytes) and len(ci["sct"]) == 200
    print("8 profile blob, null, call signalling ok")
    print("(compression: run the server with {ws_compress, true}; websockets negotiates deflate by itself)")
asyncio.run(main()); print("ALL OK")
