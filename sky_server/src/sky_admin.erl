%% The admin web page (/admin) and its JSON interface (/admin/api/...).
%% - Without a password file (scripts/admin_password.sh set) the page tells how to make one and nothing else works.
%% - Login with the password -> a random session cookie (HttpOnly, SameSite=Strict, 12 hours). Every API call must also carry the header
%%   "X-Sky-Admin: 1" (a web page of another site cannot add it). 5 wrong passwords lock that address for 15 minutes.
%% - It is served on its own listeners (see sky_app): http on port 8080 and https on port 8443, by default only on this machine (admin_listen_ip).
%%   Nothing is behind Caddy, so the address of the visitor is the address of the connection (X-Forwarded-For is ignored).
%% - `admin_allow` in config/sys.config can limit who may open it at all: [<<"203.0.113.5">>, <<"192.168.0.0/16">>] (IPv4).
-module(sky_admin).
-behaviour(cowboy_handler).
-export([init/2]).

init(Req, Kind) ->
    case ip_allowed(client_ip(Req)) of
        false -> {ok, cowboy_req:reply(404, #{}, <<>>, Req), Kind};
        true ->
            Method = cowboy_req:method(Req),
            Reply = case Kind of
                root -> cowboy_req:reply(302, #{<<"location">> => <<"/admin">>}, Req);
                page -> page(Method, Req);
                api -> api(Method, cowboy_req:path_info(Req), Req)
            end,
            {ok, Reply, Kind}
    end.

%% ---------------- the page ----------------

page(<<"GET">>, Req) ->
    case file:read_file(filename:join(code:priv_dir(sky), "admin/index.html")) of
        {ok, Html} ->
            cowboy_req:reply(200, #{<<"content-type">> => <<"text/html; charset=utf-8">>,
                                    <<"cache-control">> => <<"no-store">>,
                                    <<"x-content-type-options">> => <<"nosniff">>,
                                    <<"referrer-policy">> => <<"no-referrer">>,
                                    <<"content-security-policy">> =>
                                        <<"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'">>},
                             Html, Req);
        _ -> cowboy_req:reply(500, #{}, <<"admin page missing">>, Req)
    end;
page(_, Req) -> cowboy_req:reply(405, #{}, <<>>, Req).

%% ---------------- the interface ----------------

api(Method, Path, Req) ->
    case cowboy_req:header(<<"x-sky-admin">>, Req) of
        <<"1">> -> api1(Method, Path, Req);
        _ -> json(400, #{error => <<"bad request">>}, Req)
    end.

api1(<<"POST">>, [<<"login">>], Req) ->
    case body(Req) of
        {ok, #{<<"password">> := Pw}, Req1} ->
            case sky_admin_srv:login(client_ip(Req1), Pw) of
                {ok, Token} ->
                    Req2 = cowboy_req:set_resp_cookie(<<"sky_admin">>, Token, Req1,
                        #{http_only => true, secure => https(Req1), same_site => strict, path => <<"/admin">>, max_age => 43200}),
                    json(200, #{ok => true}, Req2);
                {error, wrong} -> json(401, #{error => <<"wrong password">>}, Req1);
                {error, locked} -> json(429, #{error => <<"too many wrong passwords: locked for 15 minutes">>}, Req1);
                {error, not_set_up} -> json(503, #{error => <<"no admin password yet: run scripts/admin_password.sh set on the server">>}, Req1)
            end;
        _ -> json(400, #{error => <<"bad request">>}, Req)
    end;
api1(Method, Path, Req) ->
    case authed(Req) of
        {true, Token} -> authed_api(Method, Path, Token, Req);
        false -> json(401, #{error => <<"login needed">>}, Req)
    end.

authed_api(<<"POST">>, [<<"logout">>], Token, Req) ->
    sky_admin_srv:logout(Token),
    json(200, #{ok => true}, cowboy_req:set_resp_cookie(<<"sky_admin">>, <<>>, Req, #{path => <<"/admin">>, max_age => 0}));
authed_api(<<"GET">>, [<<"me">>], _, Req) -> json(200, #{ok => true}, Req);
authed_api(<<"GET">>, [<<"stats">>], _, Req) -> json(200, stats(), Req);
authed_api(<<"GET">>, [<<"settings">>], _, Req) -> json(200, #{settings => settings_json(), console_secrets => sky_console:enabled()}, Req);
authed_api(<<"POST">>, [<<"settings">>], _, Req) ->
    case body(Req) of
        {ok, In0, Req1} when is_map(In0) ->
            {Con, In1} = case maps:take(<<"console_secrets">>, In0) of {D, R} when is_boolean(D) -> {D, R}; _ -> {undefined, In0} end,
            In = case maps:find(<<"turn_secret">>, In1) of {ok, <<>>} -> maps:remove(<<"turn_secret">>, In1); _ -> In1 end,   %% empty = keep
            case sky_settings:put_many(In) of
                ok ->
                    case Con of true -> sky_console:on(); false -> sky_console:off(); _ -> ok end,
                    json(200, #{ok => true}, Req1);
                {error, Msg} -> json(400, #{error => Msg}, Req1)
            end;
        _ -> json(400, #{error => <<"bad request">>}, Req)
    end;
authed_api(<<"GET">>, [<<"gateways">>], _, Req) ->
    json(200, #{gateways => sky_sms_gw:read_list(), status => gw_status()}, Req);
authed_api(<<"POST">>, [<<"gateways">>], _, Req) ->
    case body(Req) of
        {ok, #{<<"gateways">> := L}, Req1} ->
            case sky_sms_gw:write_list(L) of
                ok -> json(200, #{ok => true}, Req1);
                {error, Msg} -> json(400, #{error => Msg}, Req1)
            end;
        _ -> json(400, #{error => <<"bad request">>}, Req)
    end;
authed_api(<<"POST">>, [<<"gateways">>, <<"test">>], _, Req) ->
    case body(Req) of
        {ok, #{<<"phone">> := Phone}, Req1} when is_binary(Phone) ->
            case re:run(Phone, "^\\+[0-9]{8,15}$") of
                nomatch -> json(400, #{error => <<"the number must look like +8801712345678">>}, Req1);
                _ ->
                    case sky_sms_gw:test(Phone) of
                        ok -> json(200, #{ok => true}, Req1);
                        {error, Why} -> json(200, #{ok => false, error => iolist_to_binary(io_lib:format("~p", [Why]))}, Req1)
                    end
            end;
        _ -> json(400, #{error => <<"bad request">>}, Req)
    end;
authed_api(<<"GET">>, [<<"reports">>], _, Req) ->
    Rs = [#{reporter => R, reported => D, ts => Ts, reason => maps:get(<<"reason">>, Data, <<>>), msgs => maps:get(<<"msgs">>, Data, [])}
          || #{reporter := R, reported := D, ts := Ts, data := Data} <- lists:sublist(sky_db:list_reports(), 100)],
    json(200, #{reports => Rs}, Req);
authed_api(_, _, _, Req) -> json(404, #{error => <<"not found">>}, Req).

%% ---------------- data ----------------

stats() ->
    G = fun(N) -> sky_stats:get(N) end,
    Tables = maps:from_list([{atom_to_binary(T, utf8), mnesia:table_info(T, size)} || T <- mnesia:system_info(tables) -- [schema]]),
    #{now => os:system_time(second), uptime => sky_stats:uptime(),
      connections => G(conns_open), online_users => ets:info(sky_online, size), logins => G(logins),
      messages => G(msgs), group_messages => G(group_msgs), calls => G(calls), otp_requests => G(otp_requests),
      rate_limited => G(rate_limited), errors => G(errors), sms_ok => G(sms_ok), sms_fail => G(sms_fail),
      console_secrets => sky_console:enabled(), run_queue => erlang:statistics(run_queue), processes => erlang:system_info(process_count),
      memory => #{total => erlang:memory(total), processes => erlang:memory(processes), ets => erlang:memory(ets), binary => erlang:memory(binary)},
      tables => Tables, gateways => gw_status(), history => sky_admin_srv:history(3600)}.

gw_status() ->
    try [maps:map(fun(_, undefined) -> null; (_, V) -> V end, G) || G <- sky_sms_gw:status_data()]
    catch _:_ -> []
    end.

settings_json() ->
    maps:fold(fun(K, #{value := V, set_here := Here}, Acc) ->
        Acc#{K => case K of
                      turn_secret -> #{configured => (V =/= undefined andalso V =/= <<>> andalso V =/= ""), set_here => Here};
                      sms_text -> #{value => unicode:characters_to_binary(V), set_here => Here};
                      stun_urls -> #{value => url_list(V), set_here => Here};
                      turn_urls -> #{value => url_list(V), set_here => Here};
                      _ -> #{value => V, set_here => Here}
                  end}
    end, #{}, sky_settings:all()).

url_list(L) when is_list(L) -> [iolist_to_binary([U]) || U <- L];
url_list(_) -> [].

%% ---------------- helpers ----------------

authed(Req) ->
    case lists:keyfind(<<"sky_admin">>, 1, cowboy_req:parse_cookies(Req)) of
        {_, Token} when Token =/= <<>> -> case sky_admin_srv:check(Token) of true -> {true, Token}; false -> false end;
        _ -> false
    end.

body(Req) ->
    case cowboy_req:read_body(Req, #{length => 200000}) of
        {ok, Data, Req1} -> case catch jsx:decode(Data, [return_maps]) of M when is_map(M) -> {ok, M, Req1}; _ -> error end;
        _ -> error
    end.

json(Status, Map, Req) ->
    cowboy_req:reply(Status, #{<<"content-type">> => <<"application/json">>, <<"cache-control">> => <<"no-store">>}, jsx:encode(Map), Req).

https(Req) -> cowboy_req:scheme(Req) =:= <<"https">>.

%% The admin listeners are reached directly (no proxy in between), so the peer is the visitor.
client_ip(Req) ->
    {Peer, _} = cowboy_req:peer(Req),
    Peer.

ip_allowed(Ip) ->
    case application:get_env(sky, admin_allow, []) of
        [] -> true;
        List -> try lists:any(fun(E) -> in_net(Ip, E) end, List) catch _:_ -> false end
    end.

in_net({_, _, _, _} = Ip, Entry) ->
    [Addr | Rest] = string:split(unicode:characters_to_list(Entry), "/"),
    {ok, {_, _, _, _} = Net} = inet:parse_address(Addr),
    Bits = case Rest of [B] -> list_to_integer(B); [] -> 32 end,
    Mask = (16#ffffffff bsl (32 - Bits)) band 16#ffffffff,
    (to_int(Ip) band Mask) =:= (to_int(Net) band Mask);
in_net(Ip, Entry) ->
    {ok, A} = inet:parse_address(unicode:characters_to_list(Entry)),
    Ip =:= A.

to_int({A, B, C, D}) -> (A bsl 24) bor (B bsl 16) bor (C bsl 8) bor D.
