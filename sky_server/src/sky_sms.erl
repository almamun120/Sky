%% Plug your SMS gateway in here (Twilio, a local BD gateway, a GSM modem...).
-module(sky_sms).
-export([send/2]).

send(_Phone, _Code) ->
    logger:warning("sky_sms:send/2 not implemented; no SMS sent"),
    ok.
