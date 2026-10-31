-module(sky_app).
-behaviour(application).
-export([start/2, stop/1]).

%% Listeners (config in config/sys.config):
%%   port      plain HTTP/ws (default 8080; `undefined` = off). Put Caddy in front for wss, or switch this off
%%             and use tls_port.
%%   tls_port  HTTPS/wss straight from this server (needs tls_certfile and tls_keyfile; `undefined` = off, the default).
%%   listen_ip address to bind (default {0,0,0,0}; use {127,0,0,1} when Caddy runs on the same machine).
%% At least one of port and tls_port must be on. Both can be on at the same time.
start(_Type, _Args) ->
    ok = sky_db:init(),
    {ok, Sup} = sky_sup:start_link(),
    Dispatch = cowboy_router:compile([{'_', [
        {"/ws", sky_ws, []},
        {"/push", sky_push_ws, []},
        {"/upload", sky_media, upload},
        {"/media/:id", sky_media, download}]}]),
    Ip = application:get_env(sky, listen_ip, {0, 0, 0, 0}),
    Env = #{env => #{dispatch => Dispatch}},
    Plain = application:get_env(sky, port, 8080),
    Tls = application:get_env(sky, tls_port, undefined),
    case Plain =:= undefined andalso Tls =:= undefined of
        true -> error({no_listener, "set port and/or tls_port in config/sys.config"});
        false -> ok
    end,
    ok = start_plain(Plain, Ip, Env),
    ok = start_tls(Tls, Ip, Env),
    {ok, Sup}.

start_plain(undefined, _, _) -> ok;
start_plain(Port, Ip, Env) ->
    {ok, _} = cowboy:start_clear(sky_http, [{ip, Ip}, {port, Port}], Env),
    ok.

start_tls(undefined, _, _) -> ok;
start_tls(Port, Ip, Env) ->
    Cert = application:get_env(sky, tls_certfile, undefined),
    Key = application:get_env(sky, tls_keyfile, undefined),
    case Cert =:= undefined orelse Key =:= undefined of
        true -> error({tls_files_missing, "set tls_certfile and tls_keyfile when tls_port is on"});
        false -> ok
    end,
    {ok, _} = cowboy:start_tls(sky_https, [{ip, Ip}, {port, Port}, {certfile, Cert}, {keyfile, Key}], Env),
    ok.

stop(_State) ->
    catch cowboy:stop_listener(sky_http),
    catch cowboy:stop_listener(sky_https),
    ok.
