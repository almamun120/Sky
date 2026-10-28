%% Holds call offers (and their ICE candidates) for a callee who is offline,
%% for at most 45 s, so a push-woken phone can still ring.
-module(sky_calls).
-behaviour(gen_server).
-export([start_link/0, hold/3, append/2, cancel/1, take/1, waiting/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2]).

-define(TTL_MS, 45000).

start_link() -> gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

hold(Cid, Callee, Frame) -> gen_server:cast(?MODULE, {hold, Cid, Callee, Frame}).
append(Cid, Frame) -> gen_server:cast(?MODULE, {append, Cid, Frame}).
cancel(Cid) -> gen_server:cast(?MODULE, {cancel, Cid}).
take(Callee) -> gen_server:call(?MODULE, {take, Callee}).
%% Is a call offer still being held for this callee?
waiting(Callee) -> gen_server:call(?MODULE, {waiting, Callee}).

init([]) ->
    erlang:send_after(10000, self(), sweep),
    {ok, #{}}.

handle_cast({hold, Cid, Callee, Frame}, S) ->
    {noreply, S#{Cid => #{callee => Callee, frames => [Frame], ts => now_ms()}}};
handle_cast({append, Cid, Frame}, S) ->
    case S of
        #{Cid := #{frames := Fs} = E} -> {noreply, S#{Cid := E#{frames := [Frame | Fs]}}};
        _ -> {noreply, S}
    end;
handle_cast({cancel, Cid}, S) -> {noreply, maps:remove(Cid, S)};
handle_cast(_, S) -> {noreply, S}.

handle_call({take, Callee}, _From, S) ->
    Now = now_ms(),
    {Mine, Rest} = maps:fold(
        fun(Cid, #{callee := C, frames := Fs, ts := Ts} = E, {M, R}) ->
                case C =:= Callee of
                    true when Now - Ts < ?TTL_MS -> {[lists:reverse(Fs) | M], R};
                    true -> {M, R};
                    false -> {M, R#{Cid => E}}
                end
        end, {[], #{}}, S),
    {reply, lists:append(Mine), Rest};
handle_call({waiting, Callee}, _From, S) ->
    Now = now_ms(),
    {reply, lists:any(fun(#{callee := C, ts := Ts}) -> C =:= Callee andalso Now - Ts < ?TTL_MS end,
                      maps:values(S)), S};
handle_call(_, _From, S) -> {reply, ok, S}.

handle_info(sweep, S) ->
    Now = now_ms(),
    erlang:send_after(10000, self(), sweep),
    {noreply, maps:filter(fun(_, #{ts := Ts}) -> Now - Ts < ?TTL_MS end, S)};
handle_info(_, S) -> {noreply, S}.

now_ms() -> erlang:monotonic_time(millisecond).
