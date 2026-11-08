# Sky server

Erlang/OTP 25+ and rebar3 required.

    rebar3 shell        # ws://127.0.0.1:4080/ws (plain listener, local only; Caddy puts wss:// on 443 in front, see "Ports and transport")

Mnesia data lives in Mnesia.<node>/ in the working directory.

## Protocol (JSON text frames)

Before login:
- {"type":"request_otp","phone":"+8801700000001"}  -> otp_sent (the code goes to the SMS gateway and, while the console switch is on, to the server console)
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

## Added in this version
- **Join approval**: `group_approval {group,on}` (admin switch); with it on, `join_invite` answers `join_pending` and admins get the request list in the `group` frame (`requests`); `approve_join {group,phone,ok}` accepts or refuses (`join_rejected` to the person). Open requests are capped per group (`too_many_requests`).
- **Communities**: `create_community {name}`, `community_add_group`, `community_remove_group`, `join_community_group`; a community has an announcement group and sub-groups (`parent`, `community`, `groups` in the `group` frame). Leaving the community cascades to its sub-groups.
- **Polls** and **broadcast media** are end-to-end client features (poll = E2E kind `poll`, votes = ctl op `vote`); the server needs no change for them.
- **Not included yet**: multi-device (linked devices). It touches every send/receive path (per-device Signal sessions, fan-out) and is left for its own step.

## Known gaps
- Session tokens never expire (add expiry + revoke on logout).
- Implement sky_sms:send/2 before real use (until then the OTP is only on the server console; switch the console lines off with scripts/console_secrets.sh off once SMS works).
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
| `{port, 4080}` | plain `ws://` / `http://`; `undefined` = off | on |
| `{tls_port, 4443}` + `tls_certfile` + `tls_keyfile` | `wss://` / `https://` served by Erlang itself | off |
| `{listen_ip, {127,0,0,1}}` | address to bind (loopback: only Caddy on this machine reaches it) | loopback |

At least one of `port` / `tls_port` must be on, otherwise the server refuses to start.

**Recommended for real use: wss with your own domain, using Caddy** (step by step):
1. Buy or use a domain and add an **A record** `chat.mydomain.com` → the public IP address of your server (wait until it resolves).
2. On the server open ports **80 and 443** (Caddy needs 80 once to get the certificate). Ports 4080 (chat behind Caddy), 8080 and 8443 (admin interface) must **not** be reachable from outside unless you chose so (see "Admin web interface").
3. In `config/sys.config` keep `{listen_ip, {127,0,0,1}}` (the default) and configure the SMS gateway, then restart the Erlang server.
4. Install Caddy (https://caddyserver.com/docs/install) and put `Caddyfile` from this folder on the server.
5. Run `SKY_DOMAIN=chat.mydomain.com caddy run --config Caddyfile` (or `systemctl enable --now caddy` with the file in `/etc/caddy/Caddyfile`).
   Caddy fetches the certificate by itself and proxies `/ws`, `/push`, `/upload` and `/media/*` to `127.0.0.1:4080`.
6. In the app (login screen, field "Server address") enter `wss://chat.mydomain.com/ws`. A green "Encrypted connection (wss)" line appears. The app
   derives `wss://…/push` and `https://…/upload`, `https://…/media/…` from the same address.
7. Test: `curl -I https://chat.mydomain.com/media/x` answers (a 4xx is fine, it means TLS and proxy work), and `ws://chat.mydomain.com/ws` over plain HTTP is redirected to https.

For development on your own network use `ws://192.168.x.x:4080/ws` (set `{listen_ip, {0,0,0,0}}`, leave `port` on, `tls_port` off). Debug and release builds of the app behave the same: they accept `ws://` and show an orange "not encrypted" line.

## Step B: wire format of /ws (JSON or MessagePack)
- Default: MessagePack (binary). JSON is only the fallback. The app names the formats it wants in the WebSocket handshake header `Sec-WebSocket-Protocol`: `sky.msgpack` (binary frames) first, `sky.json` (text frames) second.
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


## Ports and transport (version 29 + SSL base)
Everything is based on TLS. Two ports face the internet, plus the call server:

| port | protocol | what | who |
|---|---|---|---|
| **443** | TCP, https / wss | chat `/ws`, push `/push`, `/upload`, `/media/*` | Caddy (the normal way in) |
| **80** | TCP, http / ws | certificate challenge, redirect to https for browsers, and the **ws:// fallback** for `/ws`, `/push`, `/upload`, `/media/*` | Caddy |
| 3478 UDP+TCP, 5349 TCP, 50000-51000 UDP | STUN / TURN / TURN over TLS / relay range | calls | your own coturn |
| **4080** | TCP, loopback only | the Erlang server behind Caddy | Sky |
| 4443 | TCP | optional: Erlang serves wss itself (`tls_port`) | Sky, off by default |
| **8080** | TCP, http | **admin web interface** (`/admin`), separate listener, loopback by default | Sky |
| **8443** | TCP, https | **admin web interface** over TLS (needs `admin_tls/cert.pem`, see `scripts/admin_cert.sh`), loopback by default | Sky |

- The app always tries `wss://` first. Only when that cannot connect at all does it try the same host with `ws://` (port 80), once, and goes back to wss at the next connection. This is
  deliberate but weaker: on the ws:// fallback the session token and file uploads travel unencrypted (message and file contents stay end-to-end encrypted). Remove the `@app` block in the port 80 part of the `Caddyfile` to switch the fallback off.
- Wire format: MessagePack (binary) by default, JSON only as the fallback (`wire_formats` default `[msgpack, json]`).

## Console: OTP and PIN lines (switch, default ON)
- While the switch is on, the server console shows `=== OTP for +8801… : 123456 ===` and, for two-step verification, `[sky] 2-step PIN of … is now …`, `… needed to register …`, `… tried for … -> ok | {error,wrong} | {error,locked}`, `… removed`.
- The switch only controls these lines. The OTP is always handed to `sky_sms:send/2` (still a stub that logs a warning), and **the reply to the app never carries the code any more** (before: `dev_code` in dev mode). Only for the automated tests set `{expose_otp_in_reply, true}`.
- Switch it while the server runs: `scripts/console_secrets.sh on | off | status | config`, or `sky_console:on().` / `sky_console:off().` / `sky_console:status().` in the Erlang shell. The switch is a small file (`console_flag_file`, default `/tmp/sky_console_secrets`; script: `SKY_CONSOLE_FLAG=/path`);
  without it `console_secrets` of `config/sys.config` decides (default `true`). `sky_console:pin_reset("+8801…").` removes a number's PIN from the console.
- A PIN or OTP on a console is a secret: this is why the switch exists. Turn it off when real users sign up.

## Calls: your own STUN/TURN server (no Google)
- The server never hands out a Google address. `login_ok` carries `ice` built from your own coturn: from `turn_urls`, or from `public_host` (or the environment variable `SKY_DOMAIN`, the same one the Caddyfile uses) it derives
  `turn:DOMAIN:3478`, `turns:DOMAIN:5349?transport=tcp` and `stun:DOMAIN:3478`. `turn_secret` (same as `static-auth-secret` in coturn) adds time-limited TURN credentials. Without any setting the list is empty and calls work inside one network only.
- Set up the server with one command on the machine that runs it: `sudo ./scripts/turn_setup.sh chat.mydomain.com [PUBLIC_IP]` (installs coturn, writes `/etc/turnserver.conf` with a random secret, prints the two lines for `sys.config`). By hand: `turnserver.conf.example`.
- Open in the firewall: 3478 udp+tcp, 5349 tcp, 50000-51000 udp. `turns:` needs a certificate as files (`CERT=… KEY=… sudo -E ./scripts/turn_setup.sh …`).
- Test: `tests/test_ice.py`. Not compiled or run here (no Erlang). `scripts/turn_setup.sh` was only syntax-checked.

## Step 1 of the feature list: file clean-up and upload quotas
- New Mnesia table `mediaown` (id, phone, size, time), filled at every upload; created automatically. Files uploaded before this step have no owner row: they are still removed by age (`media_ttl_days`), but do not count for quotas.
- **Quota per person** (config, MB): `upload_quota_day_mb` = uploaded in the last 24 hours (default 200), `upload_quota_total_mb` = stored on the server at the same time (default 1000). 0 = no limit.
  Over the limit: `POST /upload` answers **429** with the body `quota_exceeded` (the file is not kept). The app treats it like a failed upload.
- **Clean-up** (hourly, `sky_janitor`): files and owner rows older than `media_ttl_days` (default 30) are deleted, which gives the quota back; owner rows of files that are gone are dropped;
  optional `media_max_total_mb`: when all uploads together are bigger, the oldest are deleted first (default off).
- Tests: `tests/test_quota.py` (server started with `{upload_quota_day_mb, 1}`). Not compiled or run here (no Erlang).

## SMS gateways over WebSocket (ported from roadmap 30b)
- The SMS code of a login is sent by a **gateway**: a program that speaks the SMS-over-WebSocket protocol (for example an Android phone with an "SMS WebSocket gateway" app that shows `ws://[YOUR_IP]:[PORT]`).
  This **server connects to the gateway** (as a WebSocket client, `ws://` or `wss://`) and sends `{"action":"send_sms","phone_number":"+8801…","message":"…","sim":1}`; the answer is
  `{"action":"send_sms_result","success":true,"message":"…","sim_used":"SIM 1","timestamp":…}` (`sim` is only sent if you set one for that gateway).
  The answer has no request number, so answers are matched to requests **in order** (one connection = one queue); if a gateway does not answer within 15 seconds its connection is dropped and re-opened and the SMS goes to the next gateway.
- **Several gateways, added / edited / deleted while the server runs:** `scripts/sms_gateways.sh list | add | edit | enable | disable | delete`, for example
  `scripts/sms_gateways.sh add ws://192.168.1.100:8000 --name "Office phone" --sim 1 --prio 10` and
  `scripts/sms_gateways.sh add wss://sms2.example.com/ws --prio 20 --extra '{"token":"abc"}'`.
  The list is the JSON file `sms_gateways.json` in the directory where the server runs (or `{sms_gateways_file, "/path"}` in `config/sys.config`; the script: `SKY_SMS_FILE=/path`). The server looks at the file every 5 seconds.
  `--insecure` accepts a self-signed certificate of a `wss://` gateway; `--extra` adds fields (a token, ...) to every request of that gateway.
- **Which gateway:** the connected ones in order of `prio` (smaller first), taking turns between equal ones; if one fails or times out the next one is tried (at most 3 tries). If none works the app gets `{"type":"error","code":"sms_failed"}` ("Could not send the SMS").
  A broken gateway is re-connected by itself (2 s, 4 s, ... up to 60 s). The text of the SMS: `{sms_text, "Your Sky verification code is ~s. ..."}`.
- **Console secrets and gateways (this base):** the code is **always** handed to the gateways, the console switch (`scripts/console_secrets.sh`) only decides whether it is also printed on the console. If **no gateway is configured at all**, nothing can be sent:
  with console secrets ON the code is only on the console and the request still counts as ok (development, as before); with console secrets OFF the app gets `sms_failed`.
- **Check it:** Erlang shell `sky_sms_gw:status().` (state, ok/fail counts, last problem per gateway), `sky_sms_gw:test("+8801...").` sends a test SMS; `/metrics` shows `sky_sms_gateway_up` and `sky_sms_gateway_sent_total`.
  Without a phone: `python3 tests/fake_sms_gateway.py --port 8000` is a pretend gateway that prints every SMS; `tests/test_sms_gateway.py` runs the whole flow (send, failover, all failed, time-out with `--slow`).
- New dependency: `gun` 2.0.1 (WebSocket client). New files: `src/sky_sms_gw.erl`, `src/sky_sms.erl` (rewritten), `scripts/sms_gateways.sh`.
- **Not compiled or run here (no Erlang):** `sky_sms_gw.erl` is written against gun 2.0.1; the first real run may need small fixes. `scripts/sms_gateways.sh` was tested in roadmap 30b.

## Registration open / closed
Admin page → Settings → "Registration open". While it is closed, a number that has no account yet gets `{"type":"error","code":"registration_closed"}` instead of a code (people who already have an account can still log in).
The app shows "New registrations are closed right now." (also in Bengali).

## Monitoring
- `GET /metrics` (Prometheus text format): connections, online users, logins, messages, calls, rate-limited requests, error frames, SMS sent ok/failed (also per gateway, and whether each gateway is connected), `sky_console_secrets` (1 while the console prints codes/PINs), uptime, Erlang processes,
  run queue, memory, rows per table. It exists only if `{metrics_token, <<"…">>}` is set, and needs `Authorization: Bearer <token>`; **Caddy answers 404 for `/metrics`** so it is not reachable from the internet; read it on the server machine:
  `SKY_METRICS_TOKEN=… scripts/health.sh` (talks to `127.0.0.1:4080`). `deploy/prometheus.yml` and `deploy/sky_alerts.yml` are a ready Prometheus setup with alerts (server down, console secrets left on, no SMS gateway, SMS failing, overloaded, memory).

## Scaling (one server machine)
- **What was wrong:** the library's default stops at 1024 open connections, and Linux's default file limit is also 1024. Both are fixed now: `{acceptors, 64}`, `{max_connections, 200000}`, `{backlog, 4096}` in `config/sys.config`.
- **Run it as a release** (`rebar3 as prod release`, then `bin/sky foreground`) with `config/vm.args` (more processes and ports; **change the cookie**), under systemd with `deploy/sky.service` (`LimitNOFILE=1048576`) and the kernel settings of `deploy/sysctl-sky.conf`.
- **How much one machine can carry:** idle phones cost memory, not CPU: roughly 20-50 KB per connection, so 100 000 phones need about 2-5 GB of RAM plus the database. Measure your own machine: `tests/load_test.py --clients 2000 --messages 20` (run it from a second machine; the server needs `{expose_otp_in_reply, true}` for this test) and watch
  `sky_beam_run_queue` (it should stay near 0) and the latency numbers it prints.
- **What is NOT done: more than one server machine.** The list of who is online, the message queue (Mnesia on this machine's disk) and the media files are all local. A second machine would need: a cluster-wide online registry (`pg` instead of the local table),
  Mnesia replicated over the machines, media on shared storage (or S3), and a load balancer that keeps a phone on one machine. Until you are near the limits of one big machine, the better use of time is a bigger machine and a backup of `Mnesia.*` and `media/`.
- Backups: stop nothing; copy the Mnesia directory and the media directory (for example with `rsync` every night to another machine).

## Admin web interface (`/admin`)
One page for the things you would otherwise do in files and shells. It has **its own two ports, separate from the chat**: **http on 8080** (`http://HOST:8080/admin`) and **https on 8443** (`https://HOST:8443/admin`). It is no longer served on the chat listener (4080) or behind Caddy.
- **Who can reach it:** by default only this machine (`admin_listen_ip {127,0,0,1}`). From your own computer use an SSH tunnel: `ssh -L 8443:127.0.0.1:8443 user@server`, then open `https://127.0.0.1:8443/admin`.
  To open it to the network set `{admin_listen_ip, {0,0,0,0}}` **and** `{admin_allow, [...]}` (your address list) in `config/sys.config`, and open 8443 (and 8080 only if you really want plain http) in the firewall. Prefer 8443: on 8080 the password travels unencrypted.
- **https certificate for 8443:** `./scripts/admin_cert.sh your-domain-or-ip` makes a self-signed pair in `admin_tls/` (the browser warns once); or point `admin_tls_certfile` / `admin_tls_keyfile` at a real certificate. Without the files only http 8080 starts (a notice is logged).
- Switches: `admin_port` (8080), `admin_tls_port` (8443), `admin_listen_ip`; `undefined` turns a port off.
- The visitor's address for `admin_allow` and the wrong-password lock is the address of the connection (nothing is in front of these ports any more, so `X-Forwarded-For` is ignored).
- **First, set a password** (without it the page does not work): on the server machine, in the directory where the server runs: `scripts/admin_password.sh set` (at least 12 characters; stored only as a salted PBKDF2 hash in `admin.json`; `remove` switches the page off). No restart needed.
- **Dashboard:** connected phones, numbers online, messages, logins, calls, SMS codes asked, SMS ok / failed, refused requests, error replies, run queue, memory, uptime, rows per table, a red warning while console secrets are on, and four charts of the last hour
  (connected phones, messages per minute, run queue, memory; sampled every 10 s, 24 hours are kept). The SMS gateways with their state (connected / down / disabled), counts and last problem.
- **SMS gateways:** a table to add, edit, delete, enable / disable gateways (address `ws://` or `wss://`, SIM, priority, "accept self-signed certificate", extra fields) and Save; the state of each; a **test SMS** button. The same list that `scripts/sms_gateways.sh` edits.
- **Settings:** console secrets on / off (the same switch as `scripts/console_secrets.sh`), new registrations allowed on / off, WebSocket compression, the wire formats offered (MessagePack / JSON), the text of the SMS. They are kept in `sky_settings.json` and win over `config/sys.config`; they act at once.
- **Calls (STUN/TURN):** STUN addresses, TURN addresses, the TURN secret (write-only: it is never shown again), validity of the credentials, and a ready **coturn configuration** to match. Phones get the new values at their next login.
  (`public_host` / `SKY_DOMAIN` still derives the TURN addresses when none are set here or in `sys.config`.)
- **Reports:** what users reported (newest 100, with the messages they chose to send along).
- **Safety:** the cookie is HttpOnly + SameSite=Strict (+ Secure on https); every call also needs the header `X-Sky-Admin: 1`; 5 wrong passwords lock that address for 15 minutes; the page loads nothing from other sites (strict content security policy);
  `{admin_allow, [...]}` in `config/sys.config` limits it to your own addresses. **Use it only over https (8443) or through an SSH tunnel.** With a long password and an address list it is as safe as an SSH login; without both, do not expose it.
- Not in the page (on purpose): changing the ports, the TLS certificate, listener sizes (they need a restart: `config/sys.config`), and deleting users.
- Files: `src/sky_admin.erl`, `src/sky_admin_srv.erl`, `src/sky_settings.erl`, `priv/admin/index.html`, `scripts/admin_password.sh`; test: `tests/test_admin.py`.
- **Not compiled or run here** (no Erlang): the Erlang parts. The page's JavaScript is syntax-checked with `node --check`.

## Version 0.24.0: what was brought over from roadmap 30b (on top of 29 + SSL base)
Base is your 29 (0.23.1). The server features that only existed in 30b were added; the SSL base, ports (4080 / 443 / 80), wss-first with ws:// fallback, own STUN/TURN, upload quotas and the console-secrets switch of 29 were **kept**:
- SMS gateways (several, failover), `/admin` page, Prometheus `/metrics` + alerts, scaling settings (acceptors, connection limit, `vm.args`, systemd unit, sysctl, `rebar3 as prod release`), `sky_stats` counters, settings file that wins over `sys.config`.
- Not copied: 30b's `dev_mode` switch (29's `console_secrets` replaces it; the app is never told the SMS code), 30b's "release without address field" / `inject_server.sh` / extra workflows (29 has one build for debug and release with the address field everywhere), and 30b's port 8080.
- Android: error texts for `sms_failed` and `registration_closed`; every English screen text that is in the Bengali table is now wrapped in `t(...)` (login, chat list, settings, chat, ...), plus two new table lines.

## Version 0.24.1 (server): admin interface moved to ports 8080 (http) and 8443 (https)
- `sky_app` starts two more listeners for the admin page only (`sky_admin_http` 8080, `sky_admin_https` 8443); `/admin` is removed from the chat listener. Everything else (4080 behind Caddy, 443/80, wss first with ws:// fallback, own STUN/TURN, quotas, SMS gateways, `/metrics` on 4080) is unchanged.
- `sky_admin`: the client address is the connection's address (no `X-Forwarded-For`), the cookie is `Secure` when the request came in over https (8443); `/` redirects to `/admin`.
- New: `scripts/admin_cert.sh`. `tests/test_admin.py` now talks to `http://127.0.0.1:8080` (`SKY_ADMIN_HTTP`). `Caddyfile`: the admin comments and block are gone.
- Not compiled or run here (no Erlang). The self-signed certificate script was run once here and checked with `openssl`.
