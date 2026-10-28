%% Tracks which phone number is connected to which process, and who is watching whom
%% (a client that has a chat open watches that contact's online status).
-module(sky_router).
-behaviour(gen_server).
-export([start_link/0, register/2, lookup/1, watch/2, changed/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2]).

-define(T, sky_online).

start_link() -> gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

register(Phone, Pid) -> gen_server:call(?MODULE, {register, Phone, Pid}).

%% Pid wants {presence_changed, Phone} messages for Phone (one target per process;
%% `undefined` stops watching).
watch(Pid, Phone) -> gen_server:cast(?MODULE, {watch, Pid, Phone}).

%% Tell the watchers of Phone that its visible status changed (e.g. privacy switch).
changed(Phone) -> gen_server:cast(?MODULE, {changed, Phone}).

lookup(Phone) ->
    case ets:lookup(?T, Phone) of
        [{_, Pid}] -> {ok, Pid};
        [] -> error
    end.

%% State: of = #{WatcherPid => Target}, by = #{Target => [WatcherPid]}
init([]) ->
    ?T = ets:new(?T, [named_table, set, protected, {read_concurrency, true}]),
    {ok, #{of => #{}, by => #{}}}.

handle_call({register, Phone, Pid}, _From, S) ->
    case ets:lookup(?T, Phone) of
        [{_, Old}] when Old =/= Pid -> Old ! kicked;
        _ -> ok
    end,
    erlang:monitor(process, Pid),
    ets:insert(?T, {Phone, Pid}),
    notify(Phone, S),
    {reply, ok, S}.

handle_cast({watch, Pid, Target}, S) ->
    S1 = unwatch(Pid, S),
    case Target of
        undefined -> {noreply, S1};
        _ when is_binary(Target) ->
            #{of := Of, by := By} = S1,
            {noreply, S1#{of := Of#{Pid => Target},
                          by := By#{Target => [Pid | maps:get(Target, By, [])]}}}
    end;
handle_cast({changed, Phone}, S) ->
    notify(Phone, S),
    {noreply, S};
handle_cast(_, S) -> {noreply, S}.

handle_info({'DOWN', _Ref, process, Pid, _Why}, S) ->
    %% Only a phone whose *current* process died goes offline (a kicked old one does not).
    Phones = [P || [P] <- ets:match(?T, {'$1', Pid})],
    ets:match_delete(?T, {'_', Pid}),
    S1 = unwatch(Pid, S),
    Now = os:system_time(millisecond),
    lists:foreach(fun(P) -> sky_db:set_last_seen(P, Now), notify(P, S1) end, Phones),
    {noreply, S1};
handle_info(_, S) -> {noreply, S}.

unwatch(Pid, #{of := Of, by := By} = S) ->
    case maps:take(Pid, Of) of
        error -> S;
        {Target, Of1} ->
            Left = lists:delete(Pid, maps:get(Target, By, [])),
            By1 = case Left of [] -> maps:remove(Target, By); _ -> By#{Target => Left} end,
            S#{of := Of1, by := By1}
    end.

notify(Phone, #{by := By}) ->
    lists:foreach(fun(W) -> W ! {presence_changed, Phone} end, maps:get(Phone, By, [])).
