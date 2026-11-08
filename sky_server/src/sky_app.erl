-module(sky_app).
-behaviour(application).
-export([start/2, stop/1]).

%% Listeners (config in config/sys.config):
%%   port      plain HTTP/ws (default 4080; `undefined` = off). Put Caddy in front for wss, or switch this off
%%             and use tls_port.
%%   tls_port  HTTPS/wss straight from this server (needs tls_certfile and tls_keyfile; `undefined` = off, the default).
%%   listen_ip address to bind (default {127,0,0,1}: only Caddy on the same machine reaches it; use {0,0,0,0} to open it).
%% At least one of port and tls_port must be on. Both can be on at the same time.
%% The admin web interface has its own two listeners, separate from the chat ones:
%%   admin_port      plain http (default 8080; `undefined` = off)
%%   admin_tls_port  https (default 8443; `undefined` = off). Needs a certificate: admin_tls_certfile / admin_tls_keyfile
%%                   (default admin_tls/cert.pem and admin_tls/key.pem; make a self-signed pair with scripts/admin_cert.sh).
%%                   Without the files this listener is not started (a notice is logged); http still works.
%%   admin_listen_ip address to bind (default {127,0,0,1}: only this machine, e.g. through an SSH tunnel; {0,0,0,0} opens it,
%%                   then also set admin_allow). The chat listeners do not serve /admin any more.
start(_Type, _Args) ->
    ok = sky_stats:init(),
    ok = sky_settings:init(),
    ok = sky_db:init(),
    {ok, Sup} = sky_sup:start_link(),
    Dispatch = cowboy_router:compile([{'_', [
        {"/ws", sky_ws, []},
        {"/push", sky_push_ws, []},
        {"/upload", sky_media, upload},
        {"/media/:id", sky_media, download},
        {"/metrics", sky_metrics, []}]}]),
    AdminDispatch = cowboy_router:compile([{'_', [
        {"/", sky_admin, root},
        {"/admin", sky_admin, page},
        {"/admin/", sky_admin, page},
        {"/admin/api/[...]", sky_admin, api}]}]),
    Ip = application:get_env(sky, listen_ip, {127, 0, 0, 1}),
    Env = #{env => #{dispatch => Dispatch}},
    Plain = application:get_env(sky, port, 4080),
    Tls = application:get_env(sky, tls_port, undefined),
    case Plain =:= undefined andalso Tls =:= undefined of
        true -> error({no_listener, "set port and/or tls_port in config/sys.config"});
        false -> ok
    end,
    ok = start_plain(Plain, Ip, Env),
    ok = start_tls(Tls, Ip, Env),
    ok = start_admin(#{env => #{dispatch => AdminDispatch}}),
    {ok, Sup}.

%% The admin web interface: http on admin_port (8080) and https on admin_tls_port (8443), nothing else is served there.
start_admin(Env) ->
    Ip = application:get_env(sky, admin_listen_ip, {127, 0, 0, 1}),
    case application:get_env(sky, admin_port, 8080) of
        undefined -> ok;
        P -> {ok, _} = cowboy:start_clear(sky_admin_http, listener([{ip, Ip}, {port, P}]), Env)
    end,
    case application:get_env(sky, admin_tls_port, 8443) of
        undefined -> ok;
        TP ->
            Cert = application:get_env(sky, admin_tls_certfile, "admin_tls/cert.pem"),
            Key = application:get_env(sky, admin_tls_keyfile, "admin_tls/key.pem"),
            case filelib:is_regular(Cert) andalso filelib:is_regular(Key) of
                true ->
                    {ok, _} = cowboy:start_tls(sky_admin_https,
                                               listener([{ip, Ip}, {port, TP}, {certfile, Cert}, {keyfile, Key}]), Env);
                false ->
                    logger:warning("admin https (port ~p) not started: no certificate at ~s / ~s. "
                                   "Make one with scripts/admin_cert.sh (or set admin_tls_certfile / admin_tls_keyfile)", [TP, Cert, Key])
            end
    end,
    ok.

start_plain(undefined, _, _) -> ok;
start_plain(Port, Ip, Env) ->
    {ok, _} = cowboy:start_clear(sky_http, listener([{ip, Ip}, {port, Port}]), Env),
    ok.

start_tls(undefined, _, _) -> ok;
start_tls(Port, Ip, Env) ->
    Cert = application:get_env(sky, tls_certfile, undefined),
    Key = application:get_env(sky, tls_keyfile, undefined),
    case Cert =:= undefined orelse Key =:= undefined of
        true -> error({tls_files_missing, "set tls_certfile and tls_keyfile when tls_port is on"});
        false -> ok
    end,
    {ok, _} = cowboy:start_tls(sky_https, listener([{ip, Ip}, {port, Port}, {certfile, Cert}, {keyfile, Key}]), Env),
    ok.

stop(_State) ->
    catch cowboy:stop_listener(sky_http),
    catch cowboy:stop_listener(sky_https),
    catch cowboy:stop_listener(sky_admin_http),
    catch cowboy:stop_listener(sky_admin_https),
    ok.

%% Sizes of a listener. Ranch's default is 1024 connections only: far too few. `acceptors` accept new connections in parallel,
%% `max_connections` is the number of open connections after which new ones wait. Both can be set in config/sys.config.
listener(SocketOpts) ->
    #{socket_opts => SocketOpts ++ [{backlog, application:get_env(sky, backlog, 4096)}],
      num_acceptors => application:get_env(sky, acceptors, 64),
      max_connections => application:get_env(sky, max_connections, 200000)}.
