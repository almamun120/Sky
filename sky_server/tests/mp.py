"""A small MessagePack codec for the tests (str, bin, int, float, bool, nil, array, map). Written from the spec,
independently of the Erlang module, so the two check each other. bytes <-> bin, str <-> str."""
import struct

def pack(o):
    if o is None: return b"\xc0"
    if o is True: return b"\xc3"
    if o is False: return b"\xc2"
    if isinstance(o, int):
        if 0 <= o <= 0x7f: return bytes([o])
        if -32 <= o < 0: return struct.pack("b", o)
        if o >= 0:
            for fmt, code, lim in (("B", 0xcc, 1 << 8), (">H", 0xcd, 1 << 16), (">I", 0xce, 1 << 32), (">Q", 0xcf, 1 << 64)):
                if o < lim: return bytes([code]) + struct.pack(fmt, o)
        for fmt, code, lim in (("b", 0xd0, 1 << 7), (">h", 0xd1, 1 << 15), (">i", 0xd2, 1 << 31), (">q", 0xd3, 1 << 63)):
            if -lim <= o: return bytes([code]) + struct.pack(fmt, o)
        raise ValueError(o)
    if isinstance(o, float): return b"\xcb" + struct.pack(">d", o)
    if isinstance(o, str):
        b = o.encode(); n = len(b)
        if n < 32: return bytes([0xa0 | n]) + b
        if n < 256: return b"\xd9" + bytes([n]) + b
        if n < 65536: return b"\xda" + struct.pack(">H", n) + b
        return b"\xdb" + struct.pack(">I", n) + b
    if isinstance(o, (bytes, bytearray)):
        n = len(o)
        if n < 256: return b"\xc4" + bytes([n]) + bytes(o)
        if n < 65536: return b"\xc5" + struct.pack(">H", n) + bytes(o)
        return b"\xc6" + struct.pack(">I", n) + bytes(o)
    if isinstance(o, (list, tuple)):
        n = len(o)
        h = bytes([0x90 | n]) if n < 16 else (b"\xdc" + struct.pack(">H", n) if n < 65536 else b"\xdd" + struct.pack(">I", n))
        return h + b"".join(pack(e) for e in o)
    if isinstance(o, dict):
        n = len(o)
        h = bytes([0x80 | n]) if n < 16 else (b"\xde" + struct.pack(">H", n) if n < 65536 else b"\xdf" + struct.pack(">I", n))
        return h + b"".join(pack(k) + pack(v) for k, v in o.items())
    raise TypeError(type(o))

def unpack(b):
    v, i = _u(b, 0)
    if i != len(b): raise ValueError("trailing bytes")
    return v

def _u(b, i):
    t = b[i]; i += 1
    if t <= 0x7f: return t, i
    if t >= 0xe0: return t - 256, i
    if 0x80 <= t <= 0x8f: return _map(b, i, t & 15)
    if 0x90 <= t <= 0x9f: return _arr(b, i, t & 15)
    if 0xa0 <= t <= 0xbf: n = t & 31; return b[i:i + n].decode(), i + n
    if t == 0xc0: return None, i
    if t == 0xc2: return False, i
    if t == 0xc3: return True, i
    if t in (0xc4, 0xc5, 0xc6):
        w = {0xc4: 1, 0xc5: 2, 0xc6: 4}[t]; n = int.from_bytes(b[i:i + w], "big"); i += w; return bytes(b[i:i + n]), i + n
    if t in (0xd9, 0xda, 0xdb):
        w = {0xd9: 1, 0xda: 2, 0xdb: 4}[t]; n = int.from_bytes(b[i:i + w], "big"); i += w; return b[i:i + n].decode(), i + n
    f = {0xca: (">f", 4), 0xcb: (">d", 8), 0xcc: ("B", 1), 0xcd: (">H", 2), 0xce: (">I", 4), 0xcf: (">Q", 8),
         0xd0: ("b", 1), 0xd1: (">h", 2), 0xd2: (">i", 4), 0xd3: (">q", 8)}
    if t in f: fmt, w = f[t]; return struct.unpack(fmt, b[i:i + w])[0], i + w
    if t in (0xdc, 0xdd): w = 2 if t == 0xdc else 4; n = int.from_bytes(b[i:i + w], "big"); return _arr(b, i + w, n)
    if t in (0xde, 0xdf): w = 2 if t == 0xde else 4; n = int.from_bytes(b[i:i + w], "big"); return _map(b, i + w, n)
    raise ValueError("unsupported type 0x%x" % t)

def _arr(b, i, n):
    out = []
    for _ in range(n): v, i = _u(b, i); out.append(v)
    return out, i

def _map(b, i, n):
    out = {}
    for _ in range(n): k, i = _u(b, i); v, i = _u(b, i); out[k] = v
    return out, i

if __name__ == "__main__":
    # the example from the MessagePack specification
    assert pack({"compact": True, "schema": 0}) == bytes.fromhex("82a7636f6d70616374c3a6736368656d6100")
    for v in [None, True, False, 0, 127, 128, 255, 256, 65535, 65536, 2**32 - 1, 2**32, 2**63, -1, -32, -33, -128, -129, -32768,
              -32769, -2**31, -2**31 - 1, 1.5, "", "a" * 31, "a" * 32, "é" * 200, "x" * 70000, b"", b"\x00" * 255, b"\x01" * 256,
              b"\x02" * 70000, [], list(range(16)), list(range(70000)), {}, {str(i): i for i in range(16)},
              {"a": [1, {"b": b"\xff"}], "t": 1730000000000}]:
        assert unpack(pack(v)) == v, v
    print("mp.py self-test ok")
