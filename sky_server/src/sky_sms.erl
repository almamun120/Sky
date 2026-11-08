%% Sends the SMS code. The real work is done by sky_sms_gw (one or more SMS gateways reached over WebSocket).
%% The console line with the code is a separate switch (sky_console, scripts/console_secrets.sh) and does not change what happens here.
-module(sky_sms).
-export([send/2]).

%% ok | {error, Reason}
%%  - gateways are configured (sms_gateways.json / admin page): the code goes through them; if none works -> {error, Why}.
%%  - no gateway is configured at all: nothing can be sent. With console secrets ON the code is on the server console and the
%%    request counts as ok (development, as before); with console secrets OFF it is an error (sms_failed), because nobody could get the code.
send(Phone, Code) ->
    case sky_sms_gw:read_list() of
        [] ->
            case sky_console:enabled() of
                true ->
                    logger:warning("no SMS gateway configured: the code for ~s is only on the server console", [Phone]),
                    ok;
                false ->
                    logger:error("no SMS gateway configured and console secrets are off: no SMS sent to ~s", [Phone]),
                    sky_stats:inc(sms_fail),
                    {error, no_gateway}
            end;
        _ ->
            Fmt = sky_settings:get(sms_text, "Your Sky verification code is ~s. Do not share it with anybody."),
            Text = unicode:characters_to_binary(io_lib:format(Fmt, [Code])),
            case sky_sms_gw:send(Phone, Text) of
                ok -> sky_stats:inc(sms_ok), ok;
                {error, Why} ->
                    sky_stats:inc(sms_fail),
                    logger:error("SMS to ~s failed: ~p", [Phone, Why]),
                    {error, Why}
            end
    end.
