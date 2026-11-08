%% GET /metrics in the Prometheus text format. Only with a token: set {metrics_token, <<"...">>} in config/sys.config
%% (without it the page does not exist) and call it with  Authorization: Bearer <token>.
%% Caddy hides /metrics from the internet (see Caddyfile); Prometheus talks to 127.0.0.1:4080 directly.
-module(sky_metrics).
-behaviour(cowboy_handler).
-export([init/2]).

init(Req, State) ->
    Reply = case application:get_env(sky, metrics_token, undefined) of
        undefined -> cowboy_req:reply(404, #{}, <<>>, Req);
        Token ->
            case cowboy_req:header(<<"authorization">>, Req) of
                <<"Bearer ", Given/binary>> ->
                    case same(Given, iolist_to_binary(Token)) of
                        true -> cowboy_req:reply(200, #{<<"content-type">> => <<"text/plain; version=0.0.4">>}, render(), Req);
                        false -> cowboy_req:reply(403, #{}, <<"forbidden">>, Req)
                    end;
                _ -> cowboy_req:reply(403, #{}, <<"forbidden">>, Req)
            end
    end,
    {ok, Reply, State}.

same(A, B) when byte_size(A) =:= byte_size(B) ->
    lists:foldl(fun({X, Y}, Acc) -> Acc bor (X bxor Y) end, 0, lists:zip(binary_to_list(A), binary_to_list(B))) =:= 0;
same(_, _) -> false.

render() ->
    M = fun(Kind) -> erlang:memory(Kind) end,
    Tables = lists:sort(mnesia:system_info(tables) -- [schema]),
    Gws = try sky_sms_gw:status_data() catch _:_ -> [] end,
    iolist_to_binary([
        g("sky_connections", "open /ws connections", sky_stats:get(conns_open)),
        c("sky_connections_total", "/ws connections opened since start", sky_stats:get(conns_total)),
        g("sky_online_users", "phone numbers with a connection", ets:info(sky_online, size)),
        c("sky_logins_total", "successful logins", sky_stats:get(logins)),
        c("sky_otp_requests_total", "SMS codes requested", sky_stats:get(otp_requests)),
        c("sky_messages_total", "one-to-one messages accepted", sky_stats:get(msgs)),
        c("sky_group_messages_total", "group messages accepted", sky_stats:get(group_msgs)),
        c("sky_calls_total", "calls started", sky_stats:get(calls)),
        c("sky_rate_limited_total", "requests refused by the rate limit", sky_stats:get(rate_limited)),
        c("sky_error_frames_total", "error frames sent to clients", sky_stats:get(errors)),
        "# TYPE sky_sms_sent_total counter\n",
        io_lib:format("sky_sms_sent_total{result=\"ok\"} ~p~nsky_sms_sent_total{result=\"fail\"} ~p~n",
                      [sky_stats:get(sms_ok), sky_stats:get(sms_fail)]),
        g("sky_uptime_seconds", "seconds since the server started", sky_stats:uptime()),
        g("sky_console_secrets", "1 while OTP codes and PINs are printed on the server console (should be 0 in production)", case sky_console:enabled() of true -> 1; false -> 0 end),
        g("sky_beam_processes", "Erlang processes", erlang:system_info(process_count)),
        g("sky_beam_run_queue", "runnable Erlang processes (high = overloaded)", erlang:statistics(run_queue)),
        "# TYPE sky_beam_memory_bytes gauge\n",
        io_lib:format("sky_beam_memory_bytes{kind=\"total\"} ~p~nsky_beam_memory_bytes{kind=\"processes\"} ~p~n"
                      "sky_beam_memory_bytes{kind=\"ets\"} ~p~nsky_beam_memory_bytes{kind=\"binary\"} ~p~n",
                      [M(total), M(processes), M(ets), M(binary)]),
        "# TYPE sky_table_rows gauge\n",
        [io_lib:format("sky_table_rows{table=\"~s\"} ~p~n", [T, mnesia:table_info(T, size)]) || T <- Tables],
        "# TYPE sky_sms_gateway_up gauge\n",
        [io_lib:format("sky_sms_gateway_up{gateway=\"~s\"} ~p~n", [maps:get(id, G), case maps:get(up, G) of true -> 1; false -> 0 end]) || G <- Gws],
        "# TYPE sky_sms_gateway_sent_total counter\n",
        [io_lib:format("sky_sms_gateway_sent_total{gateway=\"~s\",result=\"ok\"} ~p~nsky_sms_gateway_sent_total{gateway=\"~s\",result=\"fail\"} ~p~n",
                       [maps:get(id, G), maps:get(ok, G), maps:get(id, G), maps:get(fail, G)]) || G <- Gws]
    ]).

g(Name, Help, V) -> io_lib:format("# HELP ~s ~s~n# TYPE ~s gauge~n~s ~p~n", [Name, Help, Name, Name, V]).
c(Name, Help, V) -> io_lib:format("# HELP ~s ~s~n# TYPE ~s counter~n~s ~p~n", [Name, Help, Name, Name, V]).
