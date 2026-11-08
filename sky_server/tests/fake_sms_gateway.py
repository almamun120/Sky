"""A pretend SMS gateway for trying the server without a phone. It speaks the same SMS-over-WebSocket protocol:
   in : {"action":"send_sms","phone_number":"+8801...","message":"...","sim":1}
   out: {"action":"send_sms_result","success":true,"message":"SMS sent successfully","sim_used":"SIM 1","timestamp":1234567890}

Run it alone:   python3 fake_sms_gateway.py --port 8000 [--mode ok|fail|silent]
then   ./scripts/sms_gateways.sh add ws://127.0.0.1:8000   and every code the server
"sends" is printed here. Used by test_sms_gateway.py as a module."""
import argparse, asyncio, json, time
import websockets

class FakeGateway:
    def __init__(self, port, mode="ok"):
        self.port, self.mode, self.received, self.server = port, mode, [], None
    async def _handler(self, ws, path=None):
        async for raw in ws:
            try: req = json.loads(raw)
            except ValueError: continue
            if req.get("action") != "send_sms": continue
            self.received.append(req)
            print(f"[gateway :{self.port}] SMS to {req.get('phone_number')}: {req.get('message')}  (sim {req.get('sim')})", flush=True)
            if self.mode == "silent": continue                      # never answers
            ok = self.mode == "ok"
            await ws.send(json.dumps({"action": "send_sms_result", "success": ok,
                                      "message": "SMS sent successfully" if ok else "SIM not ready",
                                      "sim_used": f"SIM {req.get('sim') or 1}", "timestamp": int(time.time())}))
    async def start(self):
        self.server = await websockets.serve(self._handler, "127.0.0.1", self.port)
        return self
    async def stop(self):
        self.server.close(); await self.server.wait_closed()

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--port", type=int, default=8000); ap.add_argument("--mode", default="ok", choices=["ok", "fail", "silent"])
    a = ap.parse_args()
    async def main():
        await FakeGateway(a.port, a.mode).start(); print(f"fake SMS gateway on ws://127.0.0.1:{a.port}  mode={a.mode}"); await asyncio.Future()
    asyncio.run(main())
