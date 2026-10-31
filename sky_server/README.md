# Sky server

Erlang/OTP 25+ and rebar3 required.

    rebar3 shell        # ws://localhost:8080/ws

Mnesia data lives in Mnesia.<node>/ in the working directory.

## Protocol (JSON text frames)

Before login:
- {"type":"request_otp","phone":"+8801700000001"}  -> otp_sent (+ dev_code in dev_mode)
- {"type":"verify_otp","phone":..,"code":"123456","name":"Rahim"} -> token, login_ok
- {"type":"auth","token":"..."} -> login_ok  (or error bad_token)

After login:
- send       {"type":"send","id":uuid,"to":phone,"body":text} -> ack | error(id)
- delivered  {"type":"delivered","id":..,"from":sender}
- read       {"type":"read","id":..,"from":sender}
- got        {"type":"got","pid":..}   (confirms a status receipt)
Server pushes: msg, status {id,status,by,pid}, error.

Messages and receipts stay in the `pending` table until the receiver
confirms them, and are re-sent on every login. Clients de-duplicate by id.

## Known gaps
- Session tokens never expire (add expiry + revoke on logout).
- Implement sky_sms:send/2 and set dev_mode=false before real use.
- No TLS: put nginx/Caddy in front (wss://).
- Push notifications: own push channel, see "Step 9" (no Firebase / Google services).

## Step 3 additions
- lookup        {"type":"lookup","phone":..} -> profile {phone, exists, name?}
- create_group  {"type":"create_group","name":..,"members":[phones]} -> every member gets a `group` frame (confirm with got/pid)
- send_group    {"type":"send_group","id":..,"group":gid,"body":..} -> ack | error(id)
- msg frames now carry `name` (sender display name) and, for groups, `group`.
- Group receipts: none yet (sender sees a single tick). No leave/add/remove yet.

## Step 4: TLS
- Run Caddy with the included Caddyfile, then use wss://your-domain/ws in the app. The Caddyfile proxies
  everything (/ws, /push, /upload, /media/*) to the server.
- (Step 4 originally used Firebase for push. It has been replaced by the own push service, see Step 9.)

## Step 5: attachments
- POST /upload   (header Authorization: Bearer <token>, raw file as body, max 25 MB) -> {"id": "<32 hex>"}
- GET  /media/<id>  (same header) -> file bytes
- Then send a normal `send` / `send_group` with extra fields:
  kind (image|audio|file), media (id), fname, mime, size, dur (ms, for audio); `body` may be empty.
- Files are stored in ./media (config: media_dir) in plain form and never deleted yet.
  Add a retention job and encryption before real use.

## Step 6: hardening
- Sessions expire after 30 days and are extended while in use; expired ones are purged hourly.
- `logout` {"type":"logout","token":..} revokes the session and closes the push channel, then the server closes the socket.
- Per-connection rate limit (burst 30, 5/s) on send, send_group, lookup, create_group, OTP calls -> error rate_limited.
- A connection must log in within 30 s or it is closed.
- Hourly janitor: undelivered items and uploaded files older than 30 days are deleted
  (config: pending_ttl_days, media_ttl_days in config/sys.config).
- Not covered yet: per-IP limits (put nginx/Caddy rate limiting in front), upload quotas per user.

## Step 7: calls (WebRTC signalling)
The server never touches audio/video; it only relays signalling between the two phones.
- call         {"type":"call","cid":uuid,"to":phone,"video":bool,"sdp":offer} -> call_state {ringing|calling} | error(cid)
- call_answer  {"type":"call_answer","cid":..,"to":caller,"sdp":answer}
- call_ice     {"type":"call_ice","cid":..,"to":peer,"cand":{sdpMid,sdpMLineIndex,candidate}}
- call_end     {"type":"call_end","cid":..,"to":peer,"reason":..}
- Server -> client: call_in, call_answered, call_ice, call_end, call_state.
- If the callee is offline the offer is held 45 s and a wake is sent over the push channel; it is delivered at their next login.
- login_ok now carries `ice` (STUN, plus TURN with short-lived credentials if configured).
- For calls over mobile data or strict NAT you need a TURN server: see turnserver.conf.example.

## Step 8: end-to-end encryption (server side)
The server stores and relays only ciphertext and public keys.
- upload_keys  {"type":"upload_keys","identity":b64,"reg":int,"spk":{id,pub,sig},"kyber":{id,pub,sig},"prekeys":[{id,pub}]}
               (a later call with only "prekeys" tops up the one-time keys)
- fetch_keys   {"type":"fetch_keys","phone":P} -> keys {identity,reg,spk,kyber,prekey?} | error no_keys
               (one one-time prekey is handed out and deleted per fetch)
- send         {"type":"send","id":..,"to":P,"ct":b64,"ctype":2|3}
- send_group   {"type":"send_group","id":..,"group":gid,"cts":{phone:{"t":2|3,"c":b64}, ...}}  (one ciphertext per member)
- Server -> client: msg {id,from,name,ct,ctype,ts[,group]}, keys_needed, prekeys_low {count}.
- With `require_e2e` (default true) plaintext `send`/`send_group` are refused with error e2e_required.
- Group names/members, receipts, timing and who-talks-to-whom stay visible to the server.
- Calls: audio/video is encrypted by WebRTC; call offers/answers are sealed in the Signal session (see Step 11).
Tests: see tests/README.md.

## Step 9: own push service (replaces Firebase)
Nothing goes through Google. Each logged-in phone keeps one tiny WebSocket open to `/push`. When something is
waiting for a phone that has no chat connection, the server sends a wake frame on it and the phone then opens its
normal chat connection and fetches everything itself. No sender, no text, nothing but a signal.

    client -> {"type":"hello","token":T}     server -> {"type":"hello_ok"}   (bad token: error bad_token, socket closed)
    client -> {"type":"ping"}                server -> {"type":"pong"}
    server -> {"type":"wake","why":"msg"|"call"}

- On `hello` the server sends a wake at once if messages (or a ringing call) are already waiting, so a phone that
  was offline cannot miss anything.
- Message wakes are limited to one per phone every 3 s; call wakes are never delayed.
- A second push connection for the same account replaces the first (close reason `replaced`).
  `logout` closes the push channel (close reason `logout`).
- Idle timeout 150 s; the app sends a WebSocket ping every 45 s.
- Code: `sky_push.erl` (registry + wake logic), `sky_push_ws.erl` (the /push socket). No configuration needed.
- Upgrading from step 8: the old `push` table (FCM tokens) is dropped automatically at start-up, the
  `fcm_service_account` setting is ignored, and the `register_push` message no longer exists.
- Behind Caddy/nginx make sure /push is proxied as a WebSocket and long-lived connections are not cut
  (the included Caddyfile does this). Many idle connections are cheap on Erlang: one process each.
- Limit: this only works while the phone's OS lets the app keep its connection (see the Android README).
Tests: `tests/test_push.py`.

### Calls without third parties
`login_ok` hands the app its STUN/TURN list. With your own coturn (`turn_urls` + `turn_secret`) the STUN address is
derived from it, so no Google server is used. Only when neither is configured does the server fall back to a public
Google STUN address, meant for development only.

## Step 10: group management and group receipts
- add_members    {"type":"add_members","group":gid,"members":[phones]}   owner only, max 50 members
- remove_member  {"type":"remove_member","group":gid,"phone":P}          owner only
- leave_group    {"type":"leave_group","group":gid}                       anyone; if the owner leaves, the next member becomes owner
- Every remaining member gets a fresh `group` frame (name, owner, members). The person who left or was removed gets
  `{"type":"group_removed","gid":..,"pid":..}` (kept until they confirm with `got`, so offline phones learn it too).
  When fewer than two members remain the group is deleted.
- Errors: not_owner, not_member, unknown_group, unknown_member, group_full.
- Receipts: a member's `delivered` / `read` for a group message (with `group` and `from`) is forwarded to the sender as
  a `status` frame that now also carries `group` and `by` (the member). Status pids are unique per member.
- Still visible to the server: group names, members, who is in which group.
Tests: `tests/test_groups.py`.

## Step 11: authenticated call signalling
The SDP offer/answer contains the DTLS fingerprint that protects the media. If the server (or anyone on the way)
could swap it, they could sit in the middle of a call. So the SDP is now encrypted with the Signal session between the
two phones and the server only relays an opaque blob:
- call         {"type":"call","cid":..,"to":P,"video":bool,"sct":b64,"sctype":2|3}
- call_answer  {"type":"call_answer","cid":..,"to":P,"sct":b64,"sctype":2|3}
- Server -> client: call_in / call_answered carry the same `sct` and `sctype` (no `sdp`).
- With `require_e2e` (default) a plain `sdp` is refused with error e2e_required (+ the `cid`); a malformed `sct` gives bad_request.
- `sct` may be up to 40000 base64 characters (video offers are large).
- ICE candidates stay readable: they are not secret, and a swapped candidate cannot get past the DTLS fingerprint check.
- The server still sees who calls whom and when.
Tests: `tests/test_calls_e2e.py`.

## Step 14: typing and online status
New frames (all need a login):
- `{"type":"typing","to":"+88017…","on":true}` or `{"type":"typing","group":"<gid>","on":true}`:
  relayed as `{"type":"typing","from":…,"on":…[,"group":…]}` to those who are online right now. Never stored.
  Group typing is accepted only from members.
- `{"type":"watch","phone":…}`: answer with `{"type":"presence","phone":…,"online":true}` or
  `{"online":false,"last_seen":<ms>}` or `{"hidden":true}`, then the same frame whenever that contact connects or
  disconnects. One watch per connection; `{"type":"unwatch"}` stops it. `{"type":"presence","phone":…}` asks once.
- `{"type":"set_privacy","presence":false}` hides my online status and last seen. It is **reciprocal**: if either
  side hides it, both get `hidden`. After login the server sends `{"type":"privacy","presence":<bool>}`.
- Last seen = the time the chat socket closed (new Mnesia table `presence`, created automatically).
  "Online" means the app is open: in the background only the push channel is connected.
- The server can see who is typing to whom (metadata, like who sends to whom); the content stays encrypted.
- Tests: `tests/test_presence.py`. **Not run**: no Erlang in the build environment, so this server code is only read through.

## Step 17: block and report
- `{"type":"block","phone":"+88017…","on":true|false}`: the server keeps the list (new Mnesia table `block`, created
  automatically; at most 1000 per person) and answers with the whole list `{"type":"blocks","phones":[…]}`. The same
  frame is sent after every login. Errors: `bad_phone`, `unknown_recipient` (blocking a number that is not registered),
  `too_many_blocks`.
- While A is blocked by B (nothing is revealed to A):
  - `send` from A to B gets a normal `ack` but is not stored or delivered;
  - `call` from A to B gets `call_state: ringing`, B sees nothing; `call_answer`, `call_ice`, `call_end` and `typing` from A to B are dropped;
  - `presence` / `watch` show `hidden` to both A and B (watchers are updated at once).
  B can still write to A. Group messages are not filtered.
- `{"type":"report","phone":…,"reason":"Spam","msgs":[{"ts":…,"b":"text"}]}`: stores one report per (reporter, reported),
  the newest replaces the older one (new table `report`). `msgs` is optional, at most 5 entries of at most 1000 bytes,
  chosen and sent by the reporter's own phone (the server cannot read messages). Answer: `{"type":"reported","phone":…}`.
  Read them with `sky_db:list_reports()` from a remote shell (newest first).
- Tests: `tests/test_block.py`. Not compiled or run here (no Erlang).

## Step 18: name and encrypted profile
- `{"type":"set_name","name":"…"}` (trimmed, 1 to 64 bytes) → `{"type":"me","name":…}`; errors `bad_name`, `bad_request`.
  The new name is what other people see on lookup and in new messages.
- `{"type":"set_profile","blob":"<base64>"}` (at most 8192 characters) → `{"type":"profile_saved","ts":…}`. The blob is sealed on the
  phone with a key the server never sees (about text, photo id and photo key). New Mnesia table `profile`, created automatically.
- `{"type":"get_profile","phone":…}` → `{"type":"profile_blob","phone":…,"blob":<base64 or null>,"ts":…}`. `blob` is null if
  nothing was stored, or if either person has blocked the other.
- Group photo and description need no server change: they are encrypted control messages between members.
- Tests: `tests/test_profile.py`. Not compiled or run here (no Erlang).

## Step 19: group admins, announce mode, invite links
- The group record gets `admins` (the owner is always an admin) and `announce`. Every `group` frame now carries `admins` and `announce`.
- `add_members`, `group_settings`, `invite_create`, `set_admin`: admins only (error `not_admin`; this replaces `not_owner`).
  `remove_member`: the owner removes anyone, an admin removes non-admins.
- `{"type":"set_admin","group":gid,"phone":p,"on":true|false}`; the owner cannot be changed (`bad_request`).
- `{"type":"group_settings","group":gid,"announce":true|false}`. With `announce` on, `send_group` from a non-admin is refused with
  `{"type":"error","code":"admins_only","id":…}`.
- `{"type":"invite_create","group":gid,"reset":false}` → `{"type":"invite","group":gid,"code":"24 hex characters"}`; the same code until `reset`
  is true. `{"type":"join_invite","code":…}` (case does not matter) adds the caller and sends the new member list to everyone. Errors:
  `bad_invite`, `group_full`. New Mnesia table `invite` (created automatically); the code is removed when the group is deleted.
- If the owner leaves, the first remaining admin becomes owner.
- Tests: `tests/test_group_admin.py`. Not compiled or run here (no Erlang).

## Step A: transport security (ws / wss)
The server can listen in two ways, each switched on or off in `config/sys.config` (both may be on):

| setting | meaning | default |
|---|---|---|
| `{port, 8080}` | plain `ws://` / `http://`; `undefined` = off | on |
| `{tls_port, 8443}` + `tls_certfile` + `tls_keyfile` | `wss://` / `https://` served by Erlang itself | off |
| `{listen_ip, {127,0,0,1}}` | address to bind (use this when Caddy runs on the same machine) | all addresses |

At least one of `port` / `tls_port` must be on, otherwise the server refuses to start.

**Recommended for real use: wss with your own domain, using Caddy** (step by step):
1. Buy or use a domain and add an **A record** `chat.mydomain.com` → the public IP address of your server (wait until it resolves).
2. On the server open ports **80 and 443** (Caddy needs 80 once to get the certificate). Port 8080 must **not** be reachable from outside.
3. In `config/sys.config` set `{listen_ip, {127,0,0,1}}` and `{dev_mode, false}` (and configure the SMS gateway), then restart the Erlang server.
4. Install Caddy (https://caddyserver.com/docs/install) and put `Caddyfile` from this folder on the server.
5. Run `SKY_DOMAIN=chat.mydomain.com caddy run --config Caddyfile` (or `systemctl enable --now caddy` with the file in `/etc/caddy/Caddyfile`).
   Caddy fetches the certificate by itself and proxies `/ws`, `/push`, `/upload` and `/media/*` to `127.0.0.1:8080`.
6. In the app (login screen, field "Server address") enter `wss://chat.mydomain.com/ws`. A green "Encrypted connection (wss)" line appears. The app
   derives `wss://…/push` and `https://…/upload`, `https://…/media/…` from the same address.
7. Test: `curl -I https://chat.mydomain.com/media/x` answers (a 4xx is fine, it means TLS and proxy work), and `ws://chat.mydomain.com/ws` over plain HTTP is redirected to https.

For development on your own network use `ws://192.168.x.x:8080/ws` (leave `port` on, `tls_port` off). The debug build of the app allows it and shows an orange
"not encrypted" line; the **release build refuses `ws://`** (cleartext traffic is switched off in its manifest).

## Step B: wire format of /ws (JSON or MessagePack)
- The app names the format it wants in the WebSocket handshake header `Sec-WebSocket-Protocol`: `sky.msgpack` (binary frames) or `sky.json` (text frames).
  The server answers with the first allowed format (see `wire_formats`) among those the client offered, and talks that format on that connection.
- `{wire_formats, [msgpack, json]}` (default): both allowed, MessagePack preferred. `[json]` = JSON only (handy for debugging), `[msgpack]` = MessagePack only.
  JSON and MessagePack clients can be connected at the same time and talk to each other.
- A client that sends no `Sec-WebSocket-Protocol` (an old client) gets **JSON exactly as before**, if `json` is in `wire_formats`; otherwise the handshake is refused (400).
  A text frame on a MessagePack connection (or the other way round) closes it with code 1003.
- The frames have the same fields in both formats. In MessagePack the byte fields `ct`, `c` (inside `cts`), `sct`, `blob`, `identity`, `pub`, `sig` are sent as
  MessagePack **bin** (raw bytes, no base64, a third smaller for ciphertext); in JSON they stay base64 strings. The server converts at the edge, so everything behind it is unchanged.
  Supported MessagePack types: nil, bool, ints, float, str, bin, array, map with string keys (no ext types). Errors: `bad_msgpack`, `bad_request`.
- `{ws_compress, true}` turns on WebSocket permessage-deflate (per connection, only for clients that ask; OkHttp asks automatically).
  It helps JSON and the text parts of MessagePack, not already encrypted bytes. Default off.
- `/push` stays JSON.
- Tests: all existing `tests/test_*.py` run unchanged (they have no protocol header, so they use JSON). New: `tests/test_msgpack.py` (MessagePack, JSON and old-style
  clients together; needs the default `wire_formats`) and `python3 mp.py` (self-test of the Python helper, no server needed).
  With a JSON-only server run `SKY_WIRE=json python3 test_msgpack.py` (only the negotiation checks).
- **Not compiled or run here** (no Erlang): `sky_msgpack.erl`, `sky_ws.erl`, `sky_app.erl`. Only `tests/mp.py` was run.

## Step 20 (roadmap): contact discovery
- `{"type":"find_contacts","hashes":["16 hex characters", …]}` (at most 500 per call) → `{"type":"contacts_found","matches":[{"phone":"+880…","name":"…"}]}`.
  The hash is the first 8 bytes of SHA-256 of `"sky-contact-v1:" + phone` (phone in +E.164 form), as lower-case hex. Your own number and people who blocked you are left out.
- New Mnesia table `phash` (hash → phone), filled when an account is created and, for existing accounts, at every start of the server.
- **Honest limit:** phone numbers can be guessed, so these hashes protect the address book from being sent in plain text, but not against someone who
  hashes many numbers (the server operator, or an attacker who calls `find_contacts` over and over). The token bucket slows this down; real abuse protection comes in the last step.
- Tests: `tests/test_contacts.py`. Not compiled or run here (no Erlang).

## Step 26 (roadmap): two-step verification PIN
- `{"type":"set_pin","pin":"123456","old":"…"}` (6 digits; `old` is needed when a PIN exists) and `{"type":"remove_pin","pin":"…"}` → `{"type":"pin_state","on":true|false}`. The same frame arrives at every login.
- A login with the SMS code (`verify_otp`) for a number that has a PIN is answered with `{"type":"pin_needed"}`; the client then sends `{"type":"verify_pin","pin":"…"}` on the **same connection** (valid for 5 minutes) and gets `token` + `login_ok`.
  Errors: `bad_pin`, `bad_pin_format`, `pin_locked` (5 wrong tries, 15 minutes), `pin_expired`.
- Stored as PBKDF2-HMAC-SHA256 (100 000 rounds, random salt) in the new table `pin`; wrong tries in the memory table `pinfail`. Needs Erlang/OTP 24.2 or newer (`crypto:pbkdf2_hmac`).
- If the number was not used for 7 days (last seen) and no phone of it is online, the PIN is deleted at the next registration.
- Tests: `tests/test_pin.py` (it locks its random number for 15 minutes, so run it with a fresh number each time, which it does itself). Not compiled or run here (no Erlang).
