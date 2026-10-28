%% One-time codes: 6 digits, 5 min expiry, 5 tries, 3 requests / 10 min / phone.
-module(sky_otp).
-behaviour(gen_server).
-export([start_link/0, request/1, verify/2]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2]).

-define(TTL, 300000).
-define(WINDOW, 600000).
-define(MAX_REQ, 3).
-define(MAX_TRIES, 5).

start_link() -> gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

request(Phone) -> gen_server:call(?MODULE, {request, Phone}).
verify(Phone, Code) -> gen_server:call(?MODULE, {verify, Phone, Code}).

init([]) ->
    erlang:send_after(60000, self(), sweep),
    {ok, #{}}.

handle_call({request, Phone}, _From, S) ->
    Now = now_ms(),
    Old = maps:get(Phone, S, #{reqs => []}),
    Reqs = [T || T <- maps:get(reqs, Old), Now - T < ?WINDOW],
    case length(Reqs) >= ?MAX_REQ of
        true ->
            {reply, {error, rate_limited}, S#{Phone => Old#{reqs => Reqs}}};
        false ->
            Code = gen_code(),
            E = #{code => Code, exp => Now + ?TTL, tries => 0, reqs => [Now | Reqs]},
            {reply, {ok, Code}, S#{Phone => E}}
    end;

handle_call({verify, Phone, Code}, _From, S) ->
    Now = now_ms(),
    case maps:find(Phone, S) of
        {ok, #{code := C, exp := Exp, tries := Tr} = E} when C =/= undefined ->
            if
                Now > Exp ->
                    {reply, {error, expired}, S#{Phone => E#{code => undefined}}};
                Tr >= ?MAX_TRIES ->
                    {reply, {error, too_many_attempts}, S#{Phone => E#{code => undefined}}};
                true ->
                    case equal(C, Code) of
                        true -> {reply, ok, S#{Phone => E#{code => undefined}}};
                        false -> {reply, {error, invalid}, S#{Phone => E#{tries => Tr + 1}}}
                    end
            end;
        _ ->
            {reply, {error, invalid}, S}
    end.

handle_cast(_, S) -> {noreply, S}.

handle_info(sweep, S) ->
    Now = now_ms(),
    S1 = maps:filter(fun(_, #{reqs := R}) ->
                             lists:any(fun(T) -> Now - T < ?WINDOW end, R)
                     end, S),
    erlang:send_after(60000, self(), sweep),
    {noreply, S1};
handle_info(_, S) -> {noreply, S}.

now_ms() -> erlang:monotonic_time(millisecond).

gen_code() ->
    <<N:32>> = crypto:strong_rand_bytes(4),
    iolist_to_binary(io_lib:format("~6..0B", [N rem 1000000])).

equal(A, B) when is_binary(B), byte_size(A) =:= byte_size(B) -> crypto:hash_equals(A, B);
equal(_, _) -> false.
