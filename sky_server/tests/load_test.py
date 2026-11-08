"""How many phones and messages can the server take? Run from another machine (or the same one) against a test server.
The server must run with {expose_otp_in_reply, true} (the test registers phones with the code the reply carries).
   python3 load_test.py --url ws://127.0.0.1:4080/ws --clients 500 --messages 20
Every client sends --messages messages to the next client (in a ring), the test measures how long each takes to arrive."""
import argparse, asyncio, base64, json, random, struct, time, websockets

async def client(i, a, phones, lat, stats):
    ph = phones[i]; dst = phones[(i + 1) % len(phones)]
    try:
        ws = await websockets.connect(a.url, max_size=None, ping_interval=None)
        await ws.send(json.dumps({"type": "request_otp", "phone": ph}))
        code = json.loads(await ws.recv())["dev_code"]
        await ws.send(json.dumps({"type": "verify_otp", "phone": ph, "code": code, "name": f"L{i}"}))
        while json.loads(await ws.recv()).get("type") != "login_ok": pass
    except Exception as e:
        stats["connect_fail"] += 1; stats["last_error"] = repr(e); return None
    stats["connected"] += 1
    return ws

async def pump(ws, lat, stats):
    try:
        async for raw in ws:
            m = json.loads(raw)
            if m.get("type") == "msg":
                sent = struct.unpack(">d", base64.b64decode(m["ct"])[:8])[0]
                lat.append((time.time() - sent) * 1000); stats["received"] += 1
            elif m.get("type") == "error": stats["errors"] += 1; stats["last_error"] = m.get("code")
    except Exception: pass

async def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--url", default="ws://127.0.0.1:4080/ws")
    ap.add_argument("--clients", type=int, default=200); ap.add_argument("--messages", type=int, default=10)
    ap.add_argument("--pause", type=float, default=1.0, help="seconds between a client's messages")
    a = ap.parse_args()
    base = random.randint(10**8, 8 * 10**8)
    phones = [f"+8801{base + i:09d}"[:14] for i in range(a.clients)]
    lat, stats = [], {"connected": 0, "connect_fail": 0, "received": 0, "errors": 0, "last_error": ""}
    t0 = time.time()
    wss = []
    for k in range(0, a.clients, 50):                      # register in batches (the server limits code requests)
        batch = await asyncio.gather(*[client(i, a, phones, lat, stats) for i in range(k, min(k + 50, a.clients))])
        wss += [(i, w) for i, w in zip(range(k, k + 50), batch) if w]
    print(f"{stats['connected']} of {a.clients} phones connected in {time.time() - t0:.1f} s ({stats['connect_fail']} failed {stats['last_error']})")
    pumps = [asyncio.create_task(pump(w, lat, stats)) for _, w in wss]
    t1 = time.time(); sent = 0
    for n in range(a.messages):
        for i, w in wss:
            ct = struct.pack(">d", time.time()) + b"x" * 90
            await w.send(json.dumps({"type": "send", "id": f"l{i}-{n}-{random.randint(0, 10**6)}", "to": phones[(i + 1) % len(phones)],
                                     "ct": base64.b64encode(ct).decode(), "ctype": 3})); sent += 1
        await asyncio.sleep(a.pause)
    await asyncio.sleep(3)
    dt = time.time() - t1
    lat.sort(); p = lambda q: lat[min(len(lat) - 1, int(len(lat) * q))] if lat else float("nan")
    print(f"sent {sent}, delivered {stats['received']} ({100 * stats['received'] / max(sent, 1):.1f} %), error frames {stats['errors']} {stats['last_error']}")
    print(f"throughput {sent / dt:.0f} messages/s   latency ms: median {p(.5):.1f}  p95 {p(.95):.1f}  p99 {p(.99):.1f}  max {lat[-1] if lat else float('nan'):.1f}")
    for t in pumps: t.cancel()
asyncio.run(main())
