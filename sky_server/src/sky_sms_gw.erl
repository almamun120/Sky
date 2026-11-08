%% SMS gateways reached over WebSocket (ws:// or wss://).
%%
%% The gateway is, for example, an Android phone with an "SMS over WebSocket" app. This server connects to it as a client and sends
%%     {"action":"send_sms","phone_number":"+8801...","message":"...","sim":1}          ("sim" only if set for that gateway)
%% and waits for
%%     {"action":"send_sms_result","success":true,"message":"SMS sent successfully","sim_used":"SIM 1","timestamp":...}
%% The result has no request number, so the answers are matched to the requests in order: one connection = one queue.
%% If a gateway does not answer within 15 s the connection is dropped and re-opened and the SMS goes to the next gateway.
%%
%% Several gateways can be configured; they are tried in order of "prio" (smaller first), taking turns between equal ones, and a
%% gateway that fails hands the SMS to the next one (at most 3 tries). The list lives in a JSON file that can be changed while the
%% server runs (scripts/sms_gateways.sh add | edit | delete | enable | disable | list); the server looks at the file every 5 seconds.
%%   [{"id":"g1","name":"Office phone","url":"ws://192.168.1.100:8000","sim":1,"prio":10,"enabled":true,
%%     "insecure":false,"extra":{"token":"..."}}]
%% "insecure":true accepts a self-signed certificate on a wss:// gateway. "extra" is added to every request of that gateway.
-module(sky_sms_gw).
-behaviour(gen_server).
-export([start_link/0, send/2, test/1, status/0, status_data/0, reload/0, read_list/0, write_list/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2]).

-define(POLL, 5000).
-define(REQ_TIMEOUT, 15000).
-define(MAX_TRIES, 3).

-record(conn, {gw, path = "/", gun, mon, stream, up = false, pending = queue:new(),
               ok = 0, fail = 0, err = <<>>, backoff = 2000, retry, last = 0}).

start_link() -> gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

%% ok | {error, no_gateway_up | all_gateways_failed | gateway_timeout}
send(Phone, Text) ->
    try gen_server:call(?MODULE, {send, Phone, Text}, 50000)
    catch exit:{timeout, _} -> {error, gateway_timeout};
          exit:{noproc, _} -> {error, no_gateway_up}
    end.

test(Phone) -> send(Phone, <<"Sky test message">>).

reload() -> gen_server:call(?MODULE, reload).

status_data() -> gen_server:call(?MODULE, status_data).

status() ->
    case status_data() of
        [] -> io:format("no SMS gateway configured (file ~s)~n", [file_name()]);
        L ->
            io:format("~-6s ~-8s ~-5s ~-6s ~-5s ~-5s ~s~n", ["id", "state", "prio", "ok", "fail", "sim", "url / last problem"]),
            lists:foreach(fun(G) ->
                State = case {maps:get(enabled, G), maps:get(up, G)} of {false, _} -> "disabled"; {_, true} -> "UP"; _ -> "down" end,
                io:format("~-6s ~-8s ~-5w ~-6w ~-5w ~-5s ~s ~s~n",
                          [maps:get(id, G), State, maps:get(prio, G), maps:get(ok, G), maps:get(fail, G),
                           case maps:get(sim, G) of undefined -> "-"; N -> integer_to_list(N) end,
                           maps:get(url, G), maps:get(err, G)]) end, L)
    end.

%% ---------------- server ----------------

init([]) ->
    self() ! poll,
    {ok, #{mtime => undefined, conns => #{}}}.

handle_call({send, Phone, Text}, From, S) ->
    {noreply, dispatch(#{from => From, phone => Phone, text => Text, tried => []}, S)};
handle_call(status_data, _From, #{conns := Cs} = S) ->
    L = [#{id => Id, name => maps:get(name, G), url => maps:get(url, G), enabled => maps:get(enabled, G), up => C#conn.up,
           prio => maps:get(prio, G), sim => maps:get(sim, G), ok => C#conn.ok, fail => C#conn.fail, err => C#conn.err}
         || {Id, #conn{gw = G} = C} <- lists:sort(maps:to_list(Cs))],
    {reply, L, S};
handle_call(reload, _From, S) -> {reply, ok, load(S#{mtime := undefined})}.

handle_cast(_, S) -> {noreply, S}.

handle_info(poll, S) ->
    erlang:send_after(?POLL, self(), poll),
    {noreply, load(S)};

handle_info({gun_up, Pid, _}, S) ->
    with_pid(Pid, S, fun(Id, #conn{path = Path} = C) ->
        Stream = gun:ws_upgrade(Pid, Path, [], #{keepalive => 30000}),
        put_conn(Id, C#conn{stream = Stream}, S) end);
handle_info({gun_upgrade, Pid, _Stream, [<<"websocket">>], _}, S) ->
    with_pid(Pid, S, fun(Id, C) ->
        logger:info("SMS gateway ~s is connected", [Id]),
        put_conn(Id, C#conn{up = true, backoff = 2000, err = <<>>}, S) end);
handle_info({gun_response, Pid, _, _, Status, _}, S) ->
    with_pid(Pid, S, fun(Id, _) -> down(Id, io_lib:format("the gateway refused the WebSocket (HTTP ~p)", [Status]), S) end);
handle_info({gun_ws, Pid, _, {text, Data}}, S) ->
    with_pid(Pid, S, fun(Id, C) -> gateway_message(Id, C, Data, S) end);
handle_info({gun_ws, Pid, _, close}, S) -> with_pid(Pid, S, fun(Id, _) -> down(Id, "closed by the gateway", S) end);
handle_info({gun_ws, Pid, _, {close, _, _}}, S) -> with_pid(Pid, S, fun(Id, _) -> down(Id, "closed by the gateway", S) end);
handle_info({gun_ws, _, _, _}, S) -> {noreply, S};
handle_info({gun_down, Pid, _, Why, _}, S) -> with_pid(Pid, S, fun(Id, _) -> down(Id, io_lib:format("~p", [Why]), S) end);
handle_info({gun_error, Pid, _, Why}, S) -> with_pid(Pid, S, fun(Id, _) -> down(Id, io_lib:format("~p", [Why]), S) end);
handle_info({'DOWN', Mon, process, Pid, Why}, #{conns := Cs} = S) ->
    case [Id || {Id, #conn{mon = M, gun = G}} <- maps:to_list(Cs), M =:= Mon, G =:= Pid] of
        [Id] -> {noreply, down(Id, io_lib:format("~p", [Why]), S)};
        _ -> {noreply, S}
    end;
handle_info({retry, Id}, #{conns := Cs} = S) ->
    case maps:find(Id, Cs) of
        {ok, #conn{gw = G, up = false, gun = undefined} = C} ->
            case maps:get(enabled, G) of
                true -> {noreply, put_conn(Id, open(G, C), S)};
                false -> {noreply, S}
            end;
        _ -> {noreply, S}
    end;
handle_info({req_timeout, Id, ReqId}, #{conns := Cs} = S) ->
    case maps:find(Id, Cs) of
        {ok, #conn{pending = Q}} ->
            case lists:keymember(ReqId, 1, queue:to_list(Q)) of
                %% answers are matched by order, so after a missing answer the connection cannot be trusted any more
                true -> {noreply, down(Id, "no answer from the gateway within 15 s", S)};
                false -> {noreply, S}
            end;
        error -> {noreply, S}
    end;
handle_info(_, S) -> {noreply, S}.

%% ---------------- sending ----------------

dispatch(#{tried := Tried} = Req, #{conns := Cs} = S) ->
    Usable = [{Id, C} || {Id, #conn{up = true, gw = G} = C} <- maps:to_list(Cs), maps:get(enabled, G), not lists:member(Id, Tried)],
    case length(Tried) >= ?MAX_TRIES orelse Usable =:= [] of
        true ->
            gen_server:reply(maps:get(from, Req), {error, case Tried of [] -> no_gateway_up; _ -> all_gateways_failed end}),
            S;
        false ->
            [{Id, #conn{gw = G, gun = Pid, stream = Stream, pending = Q} = C} | _] =
                lists:sort(fun({_, A}, {_, B}) -> {maps:get(prio, A#conn.gw), A#conn.last} =< {maps:get(prio, B#conn.gw), B#conn.last} end, Usable),
            Body0 = maps:merge(maps:get(extra, G), #{<<"action">> => <<"send_sms">>,
                                                      <<"phone_number">> => maps:get(phone, Req), <<"message">> => maps:get(text, Req)}),
            Body = case maps:get(sim, G) of undefined -> Body0; Sim -> Body0#{<<"sim">> => Sim} end,
            ReqId = make_ref(),
            gun:ws_send(Pid, Stream, {text, jsx:encode(Body)}),
            Timer = erlang:send_after(?REQ_TIMEOUT, self(), {req_timeout, Id, ReqId}),
            put_conn(Id, C#conn{pending = queue:in({ReqId, Req#{tried := [Id | Tried], timer => Timer}}, Q),
                                last = erlang:monotonic_time(millisecond)}, S)
    end.

gateway_message(Id, #conn{pending = Q} = C, Data, S) ->
    case catch jsx:decode(Data, [return_maps]) of
        #{<<"action">> := <<"send_sms_result">>} = M ->
            case queue:out(Q) of
                {{value, {_ReqId, #{timer := T} = Req}}, Q1} ->
                    erlang:cancel_timer(T),
                    case maps:get(<<"success">>, M, false) of
                        true ->
                            gen_server:reply(maps:get(from, Req), ok),
                            {noreply, put_conn(Id, C#conn{pending = Q1, ok = C#conn.ok + 1}, S)};
                        _ ->
                            Why = maps:get(<<"message">>, M, <<"the gateway reported a failure">>),
                            logger:warning("SMS gateway ~s: ~s", [Id, Why]),
                            S1 = put_conn(Id, C#conn{pending = Q1, fail = C#conn.fail + 1, err = to_bin(Why)}, S),
                            {noreply, dispatch(Req, S1)}
                    end;
                {empty, _} -> {noreply, S}
            end;
        _ -> {noreply, S}                       %% SIM info, greetings, ...: not needed
    end.

%% ---------------- connections ----------------

with_pid(Pid, #{conns := Cs} = S, Fun) ->
    case [{Id, C} || {Id, #conn{gun = G} = C} <- maps:to_list(Cs), G =:= Pid] of
        [{Id, C}] -> {noreply, case Fun(Id, C) of {noreply, S1} -> S1; S1 -> S1 end};
        _ -> {noreply, S}
    end.

put_conn(Id, C, #{conns := Cs} = S) -> S#{conns := Cs#{Id => C}}.

%% The connection is gone: give every waiting SMS to another gateway and try again later.
down(Id, Why, #{conns := Cs} = S0) ->
    case maps:find(Id, Cs) of
        error -> S0;
        {ok, #conn{gw = G, gun = Pid, mon = Mon, pending = Q, backoff = B, fail = F} = C} ->
            catch erlang:demonitor(Mon, [flush]),
            catch gun:close(Pid),
            Reason = to_bin(Why),
            logger:warning("SMS gateway ~s is down: ~s", [Id, Reason]),
            Waiting = [R || {_, R} <- queue:to_list(Q)],
            [catch erlang:cancel_timer(T) || #{timer := T} <- Waiting],
            C1 = C#conn{gun = undefined, mon = undefined, stream = undefined, up = false, pending = queue:new(),
                        err = Reason, fail = F + length(Waiting), backoff = min(B * 2, 60000),
                        retry = case maps:get(enabled, G) of true -> erlang:send_after(B, self(), {retry, Id}); false -> undefined end},
            S1 = put_conn(Id, C1, S0),
            lists:foldl(fun(Req, Acc) -> dispatch(Req, Acc) end, S1, Waiting)
    end.

open(Gw, C) ->
    case parse_url(maps:get(url, Gw)) of
        {ok, Scheme, Host, Port, Path} ->
            Transport = case Scheme of <<"wss">> -> tls; _ -> tcp end,
            Opts0 = #{transport => Transport, protocols => [http], retry => 0},
            Opts = case Transport of tls -> Opts0#{tls_opts => tls_opts(Gw, Host)}; tcp -> Opts0 end,
            case gun:open(Host, Port, Opts) of
                {ok, Pid} -> C#conn{path = Path, gun = Pid, mon = erlang:monitor(process, Pid), stream = undefined, up = false};
                {error, Why} -> C#conn{err = to_bin(io_lib:format("~p", [Why])),
                                       retry = erlang:send_after(C#conn.backoff, self(), {retry, maps:get(id, Gw)})}
            end;
        error -> C#conn{err = <<"the address is not a valid ws:// or wss:// address">>}
    end.

tls_opts(#{insecure := true}, _) -> [{verify, verify_none}];
tls_opts(_, Host) ->
    [{verify, verify_peer}, {cacerts, public_key:cacerts_get()}, {server_name_indication, Host},
     {customize_hostname_check, [{match_fun, public_key:pkix_verify_hostname_match_fun(https)}]}].

parse_url(Url) ->
    case uri_string:parse(Url) of
        #{scheme := Scheme, host := Host} = M when Scheme =:= <<"ws">>; Scheme =:= <<"wss">> ->
            Port = maps:get(port, M, case Scheme of <<"wss">> -> 443; _ -> 80 end),
            P = case maps:get(path, M, <<>>) of <<>> -> <<"/">>; X -> X end,
            Path = case maps:get(query, M, undefined) of undefined -> P; Q -> <<P/binary, "?", Q/binary>> end,
            {ok, Scheme, binary_to_list(Host), Port, binary_to_list(Path)};
        _ -> error
    end.

%% ---------------- the list of gateways (JSON file) ----------------

%% For the admin page: the list as written in the file (maps with binary keys), and a checked replacement of the whole list.
read_list() ->
    case file:read_file(file_name()) of
        {ok, Bin} -> case catch jsx:decode(Bin, [return_maps]) of L when is_list(L) -> [clean_quiet(X) || X <- L]; _ -> [] end;
        _ -> []
    end.

clean_quiet(M) -> try clean(M) catch _:_ -> M end.

write_list(L) when is_list(L), length(L) =< 20 ->
    try
        Clean = [clean(M) || M <- L],
        Ids = [maps:get(<<"id">>, M) || M <- Clean],
        length(Ids) =:= length(lists:usort(Ids)) orelse throw({bad, <<"two gateways have the same id">>}),
        File = file_name(), Tmp = File ++ ".tmp",
        ok = file:write_file(Tmp, jsx:prettify(jsx:encode(Clean))),
        ok = file:rename(Tmp, File),
        gen_server:call(?MODULE, reload)
    catch
        throw:{bad, Msg} -> {error, Msg};
        _:_ -> {error, <<"invalid gateway list">>}
    end;
write_list(_) -> {error, <<"at most 20 gateways">>}.

clean(#{<<"id">> := Id, <<"url">> := Url} = M) when is_binary(Id), is_binary(Url) ->
    re:run(Id, "^[A-Za-z0-9_-]{1,16}$") =/= nomatch orelse throw({bad, <<"the id must be 1-16 letters, digits, - or _">>}),
    parse_url(Url) =/= error orelse throw({bad, <<"the address must start with ws:// or wss://">>}),
    Sim = case maps:get(<<"sim">>, M, null) of 1 -> 1; 2 -> 2; null -> null; _ -> throw({bad, <<"sim must be 1, 2 or empty">>}) end,
    Prio = case maps:get(<<"prio">>, M, 100) of P when is_integer(P), P >= 0, P =< 9999 -> P; _ -> throw({bad, <<"prio must be a number">>}) end,
    Extra = case maps:get(<<"extra">>, M, #{}) of E when is_map(E) -> E; _ -> throw({bad, <<"extra must be a JSON object">>}) end,
    Name = case maps:get(<<"name">>, M, Id) of N when is_binary(N), byte_size(N) =< 60 -> N; _ -> Id end,
    #{<<"id">> => Id, <<"name">> => Name, <<"url">> => Url, <<"sim">> => Sim, <<"prio">> => Prio,
      <<"enabled">> => maps:get(<<"enabled">>, M, true) =/= false, <<"insecure">> => maps:get(<<"insecure">>, M, false) =:= true,
      <<"extra">> => Extra};
clean(_) -> throw({bad, <<"every gateway needs an id and an address">>}).

file_name() -> application:get_env(sky, sms_gateways_file, "sms_gateways.json").

load(#{mtime := Old} = S) ->
    File = file_name(),
    Mt = filelib:last_modified(File),
    case Mt =:= Old of
        true -> S;
        false ->
            case read_file(File) of
                {ok, List} -> apply_list(List, S#{mtime := Mt});
                {error, Why} ->
                    logger:warning("SMS gateways: cannot read ~s: ~p (the old list stays)", [File, Why]),
                    S#{mtime := Mt}
            end
    end.

read_file(File) ->
    case file:read_file(File) of
        {ok, Bin} ->
            case catch jsx:decode(Bin, [return_maps]) of
                L when is_list(L) -> {ok, [G || {ok, G} <- [norm(X) || X <- L]]};
                _ -> {error, not_a_json_list}
            end;
        {error, enoent} -> {ok, []};
        {error, Why} -> {error, Why}
    end.

norm(#{<<"id">> := Id, <<"url">> := Url} = M) when is_binary(Id), is_binary(Url) ->
    case parse_url(Url) of
        error -> logger:warning("SMS gateway ~s: ~s is not a ws:// or wss:// address", [Id, Url]), error;
        _ ->
            {ok, #{id => Id, name => maps:get(<<"name">>, M, Id), url => Url,
                   sim => case maps:get(<<"sim">>, M, null) of I when is_integer(I) -> I; _ -> undefined end,
                   enabled => maps:get(<<"enabled">>, M, true) =/= false,
                   prio => case maps:get(<<"prio">>, M, 100) of P when is_integer(P) -> P; _ -> 100 end,
                   insecure => maps:get(<<"insecure">>, M, false) =:= true,
                   extra => case maps:get(<<"extra">>, M, #{}) of E when is_map(E) -> E; _ -> #{} end}}
    end;
norm(_) -> error.

apply_list(List, #{conns := Old} = S) ->
    New = maps:from_list([{maps:get(id, G), G} || G <- List]),
    %% gateways that were removed
    S1 = lists:foldl(fun(Id, Acc) ->
                         case maps:is_key(Id, New) of
                             true -> Acc;
                             false -> #{conns := Cs} = Acc1 = down(Id, "removed from the list", Acc),
                                      case maps:find(Id, Cs) of
                                          {ok, #conn{retry = R}} when R =/= undefined -> erlang:cancel_timer(R);
                                          _ -> ok
                                      end,
                                      Acc1#{conns := maps:remove(Id, Cs)}
                         end
                     end, S, maps:keys(Old)),
    maps:fold(fun(Id, G, Acc) ->
                  #{conns := Cs} = Acc,
                  case maps:find(Id, Cs) of
                      {ok, #conn{gw = G}} -> Acc;                                 %% unchanged
                      {ok, #conn{retry = R} = C} ->                               %% changed: start again with the new values
                          case R of undefined -> ok; _ -> erlang:cancel_timer(R) end,
                          Acc2 = down(Id, "changed", Acc),
                          #{conns := Cs2} = Acc2,
                          #conn{} = C2 = maps:get(Id, Cs2),
                          C3 = C2#conn{gw = G, backoff = 2000, retry = undefined, ok = C#conn.ok, fail = C#conn.fail},
                          put_conn(Id, start(G, C3), Acc2);
                      error -> put_conn(Id, start(G, #conn{gw = G}), Acc)
                  end
              end, S1, New).

start(#{enabled := true} = G, C) -> open(G, C#conn{gw = G});
start(G, C) -> C#conn{gw = G}.

to_bin(X) when is_binary(X) -> X;
to_bin(X) -> unicode:characters_to_binary(lists:flatten(io_lib:format("~s", [X]))).
