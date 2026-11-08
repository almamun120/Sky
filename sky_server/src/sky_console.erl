%% Secrets on the server console: the SMS codes (OTP) and the two-step PINs.
%% Default ON. The only thing the switch controls is whether these lines are printed. It does not change what the
%% app is told and it does not stop the SMS gateway (sky_sms:send/2) from being called.
%% Switch it while the server runs, no restart:
%%     scripts/console_secrets.sh on | off | status | config      (from a shell on the server machine)
%%     sky_console:on(). sky_console:off(). sky_console:status().  (from the Erlang shell)
%% It works through a small flag file (config `console_flag_file`, default /tmp/sky_console_secrets) that holds "on" or "off".
%% Without that file the config value `console_secrets` of config/sys.config decides (default true).
-module(sky_console).
-export([enabled/0, on/0, off/0, status/0, config/0, log/2, otp/2, pin_reset/1]).

flag_file() -> application:get_env(sky, console_flag_file, "/tmp/sky_console_secrets").

enabled() ->
    case file:read_file(flag_file()) of
        {ok, B} ->
            case string:trim(B) of
                <<"on">> -> true;
                <<"off">> -> false;
                _ -> from_config()
            end;
        _ -> from_config()
    end.

from_config() -> application:get_env(sky, console_secrets, true) =/= false.

on() -> set(<<"on">>).
off() -> set(<<"off">>).

%% Forget the flag file: `console_secrets` of sys.config counts again.
config() -> _ = file:delete(flag_file()), status().

status() ->
    S = enabled(),
    io:format("~nconsole secrets (OTP and PIN lines) are ~s (flag file ~s)~n", [if S -> "ON"; true -> "OFF" end, flag_file()]),
    S.

set(Word) ->
    case file:write_file(flag_file(), <<Word/binary, "\n">>) of
        ok -> status();
        {error, Why} -> io:format("cannot write ~s: ~p~n", [flag_file(), Why]), {error, Why}
    end.

%% A line on the console, only while the switch is on.
log(Fmt, Args) ->
    case enabled() of
        true -> io:format("~n[sky] " ++ Fmt ++ "~n", Args), ok;
        false -> ok
    end.

otp(Phone, Code) ->
    case enabled() of
        true -> io:format("~n=== OTP for ~s : ~s ===~n", [Phone, Code]), ok;
        false -> ok
    end.

%% Console helper: removes the two-step PIN (and a lock after wrong tries) of a number.
pin_reset(Phone) ->
    ok = sky_db:del_pin(Phone),
    io:format("two-step PIN of ~s removed~n", [Phone]), ok.
