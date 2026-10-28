%% Sky's own push service (no Google, no Firebase).
%%
%% Every logged-in phone keeps one tiny WebSocket open to /push (see sky_push_ws). When something
%% arrives for a phone that has no chat connection, this module sends it a {"type":"wake"} frame
%% on that channel. The phone then opens its normal chat connection and fetches everything itself.
%% No message text and no sender ever go over the push channel.
-module(sky_push).
-behaviour(gen_server).
-export([start_link/0, register/2, notify/2, disconnect/1, connected/1]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2]).

-define(T, sky_push_conns).
-define(MIN_GAP_MS, 3000).      %% at most one message wake per phone every 3 s (calls always go through)

start_link() -> gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

%% Called by sky_push_ws after the phone proved its identity. A newer channel replaces the older one.
register(Phone, Pid) -> gen_server:call(?MODULE, {register, Phone, Pid}).

%% Why = msg | call
notify(Phone, Why) -> gen_server:cast(?MODULE, {notify, Phone, Why}).

%% Close the push channel of a phone (used on logout).
disconnect(Phone) -> gen_server:cast(?MODULE, {disconnect, Phone}).

connected(Phone) -> ets:member(?T, Phone).

init([]) ->
    ?T = ets:new(?T, [named_table, set, protected, {read_concurrency, true}]),
    {ok, #{last => #{}}}.

handle_call({register, Phone, Pid}, _From, S) ->
    case ets:lookup(?T, Phone) of
        [{_, Old}] when Old =/= Pid -> Old ! replaced;
        _ -> ok
    end,
    erlang:monitor(process, Pid),
    ets:insert(?T, {Phone, Pid}),
    {reply, ok, S};
handle_call(_, _From, S) -> {reply, error, S}.

handle_cast({notify, Phone, Why}, #{last := Last0} = S) ->
    Now = erlang:monotonic_time(millisecond),
    Last = case map_size(Last0) > 5000 of true -> #{}; false -> Last0 end,
    %% monotonic time may be negative, so "never notified" must not be encoded as a number
    Recent = case maps:find(Phone, Last) of
        {ok, T} -> Now - T < ?MIN_GAP_MS;
        error -> false
    end,
    case Recent andalso Why =/= call of
        true -> {noreply, S#{last := Last}};
        false ->
            case ets:lookup(?T, Phone) of
                [{_, Pid}] -> Pid ! {wake, Why}, {noreply, S#{last := Last#{Phone => Now}}};
                [] -> {noreply, S#{last := Last}}    %% not connected: it catches up when it reconnects
            end
    end;
handle_cast({disconnect, Phone}, S) ->
    case ets:lookup(?T, Phone) of
        [{_, Pid}] -> Pid ! logout;
        [] -> ok
    end,
    {noreply, S};
handle_cast(_, S) -> {noreply, S}.

handle_info({'DOWN', _Ref, process, Pid, _Why}, S) ->
    ets:match_delete(?T, {'_', Pid}),
    {noreply, S};
handle_info(_, S) -> {noreply, S}.
