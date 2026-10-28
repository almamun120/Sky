# Server tests (run against a live server)

    pip install websockets requests
    # terminal 1 (needs the built release, or: rebar3 shell)
    # terminal 2:
    python3 test_e2e_relay.py          # default server config (require_e2e = true)
    python3 test_groups.py             # group management and per-member receipts
    python3 test_calls_e2e.py          # sealed call offers/answers
    python3 test_push.py               # own push service (/push)
    python3 test_presence.py           # typing, online/last seen, privacy
    python3 test_block.py              # block and report
    python3 test_profile.py            # display name, encrypted profile blob
    python3 test_group_admin.py        # group admins, only-admins mode, invite links
    python3 test_plaintext_legacy.py   # only with {require_e2e, false} in config/sys.config

They cover (test_push.py: hello, bad token, ping, wake on message/call, throttling, catch-up on connect,
replacement, logout): OTP signup, tokens, receipts, offline queue, groups, media upload/download, calls
(online and offline ring), rate limiting, logout, key upload/fetch/one-time prekey use,
ciphertext relay, per-member group ciphertexts and refusal of plaintext.
They do not test the Android app or the cryptography itself (the server never sees plaintext).
