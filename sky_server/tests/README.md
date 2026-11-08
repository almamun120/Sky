# Server tests (run against a live server)

    pip install websockets requests
    # terminal 1: the server with {expose_otp_in_reply, true} in config/sys.config (the tests read the OTP from the reply;
    #   the app never gets it in real use). Ports: the plain listener is 127.0.0.1:4080 (8080/8443 are free).
    # terminal 2:
    python3 test_e2e_relay.py          # default server config (require_e2e = true)
    python3 test_groups.py             # group management and per-member receipts
    python3 test_calls_e2e.py          # sealed call offers/answers
    python3 test_push.py               # own push service (/push)
    python3 test_presence.py           # typing, online/last seen, privacy
    python3 test_block.py              # block and report
    python3 test_profile.py            # display name, encrypted profile blob
    python3 test_group_admin.py        # group admins, only-admins mode, invite links
    python3 mp.py                      # self-test of the Python MessagePack helper (no server needed)
    python3 test_contacts.py           # contact discovery by hashed phone numbers
    python3 test_pin.py                # two-step verification PIN (uses a new random number each time)
    python3 test_quota.py              # upload quota (server with {upload_quota_day_mb, 1})
    python3 test_ice.py                # no Google STUN/TURN address handed to the app
    python3 test_msgpack.py            # JSON + MessagePack clients together (server default config)
    python3 test_plaintext_legacy.py   # only with {require_e2e, false} in config/sys.config
    python3 test_sms_gateway.py [--slow]  # SMS gateways: send, failover, all-fail (run on the server machine; uses fake_sms_gateway.py)
    python3 test_admin.py [--lock]     # the admin web interface (run on the server machine)
    python3 load_test.py --clients 500    # capacity test (server with {expose_otp_in_reply, true})

They cover (test_push.py: hello, bad token, ping, wake on message/call, throttling, catch-up on connect,
replacement, logout): OTP signup, tokens, receipts, offline queue, groups, media upload/download, calls
(online and offline ring), rate limiting, logout, key upload/fetch/one-time prekey use,
ciphertext relay, per-member group ciphertexts and refusal of plaintext.
They do not test the Android app or the cryptography itself (the server never sees plaintext).
