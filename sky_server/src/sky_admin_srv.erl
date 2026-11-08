%% Admin page support: logins (sessions, lock after wrong passwords) and a 24-hour history of the numbers shown in the charts.
-module(sky_admin_srv).
-behaviour(gen_server).
-export([start_link/0, login/2, check/1, logout/1, history/1, admin_file/0]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2]).

-define(SESSION_MS, 12 * 3600 * 1000).
-define(SAMPLE_MS, 10000).
-define(KEEP, 8640).                      %% 24 hours of 10-second samples
-define(MAX_FAILS, 5).
-define(LOCK_MS, 900000).

start_link() -> gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

admin_file() -> application:get_env(sky, admin_file, "admin.json").

%% {ok, Token} | {error, wrong | locked | not_set_up}
login(Ip, Password) -> gen_server:call(?MODULE, {login, Ip, Password}).
check(Token) -> gen_server:call(?MODULE, {check, Token}).
logout(Token) -> gen_server:cast(?MODULE, {logout, Token}).
%% the last [Seconds] seconds, oldest first
history(Seconds) -> gen_server:call(?MODULE, {history, Seconds}).

init([]) ->
    erlang:send_after(?SAMPLE_MS, self(), sample),
    {ok, #{sessions => #{}, fails => #{}, hist => queue:new(), n => 0}}.

handle_call({login, Ip, Password}, _From, #{sessions := Ss, fails := Fs} = S) ->
    Now = now_ms(),
    case maps:get(Ip, Fs, {0, 0}) of
        {_, Until} when Until > Now -> {reply, {error, locked}, S};
        {Count, _} ->
            case verify(Password) of
                not_set_up -> {reply, {error, not_set_up}, S};
                true ->
                    Token = urlsafe(base64:encode(crypto:strong_rand_bytes(32))),
                    {reply, {ok, Token}, S#{sessions := Ss#{Token => Now + ?SESSION_MS}, fails := maps:remove(Ip, Fs)}};
                false ->
                    C = Count + 1,
                    New = case C >= ?MAX_FAILS of true -> {0, Now + ?LOCK_MS}; false -> {C, 0} end,
                    {reply, {error, wrong}, S#{fails := Fs#{Ip => New}}}
            end
    end;
handle_call({check, Token}, _From, #{sessions := Ss} = S) ->
    {reply, case maps:find(Token, Ss) of {ok, Exp} -> Exp > now_ms(); error -> false end, S};
handle_call({history, Seconds}, _From, #{hist := H} = S) ->
    Since = os:system_time(second) - Seconds,
    {reply, [X || #{t := T} = X <- queue:to_list(H), T >= Since], S}.

handle_cast({logout, Token}, #{sessions := Ss} = S) -> {noreply, S#{sessions := maps:remove(Token, Ss)}};
handle_cast(_, S) -> {noreply, S}.

handle_info(sample, #{hist := H, sessions := Ss, fails := Fs} = S) ->
    erlang:send_after(?SAMPLE_MS, self(), sample),
    Sample = #{t => os:system_time(second),
               conns => sky_stats:get(conns_open),
               msgs => sky_stats:get(msgs) + sky_stats:get(group_msgs),
               logins => sky_stats:get(logins),
               run_queue => erlang:statistics(run_queue),
               mem => erlang:memory(total)},
    H1 = queue:in(Sample, H),
    H2 = case queue:len(H1) > ?KEEP of true -> queue:drop(H1); false -> H1 end,
    Now = now_ms(),
    {noreply, S#{hist := H2,
                 sessions := maps:filter(fun(_, E) -> E > Now end, Ss),
                 fails := maps:filter(fun(_, {_, U}) -> U > Now; (_, _) -> true end, Fs)}};
handle_info(_, S) -> {noreply, S}.

now_ms() -> erlang:system_time(millisecond).

urlsafe(B) ->
    binary:replace(binary:replace(binary:replace(B, <<"+">>, <<"-">>, [global]), <<"/">>, <<"_">>, [global]), <<"=">>, <<>>, [global]).

%% admin.json: {"salt":"hex","iter":200000,"hash":"hex"} written by scripts/admin_password.sh
verify(Password) when is_binary(Password), byte_size(Password) =< 200 ->
    case file:read_file(admin_file()) of
        {ok, Bin} ->
            try
                #{<<"salt">> := Salt, <<"iter">> := Iter, <<"hash">> := Hash} = jsx:decode(Bin, [return_maps]),
                Calc = crypto:pbkdf2_hmac(sha256, Password, binary:decode_hex(Salt), Iter, 32),
                same(Calc, binary:decode_hex(Hash))
            catch _:_ -> not_set_up
            end;
        _ -> not_set_up
    end;
verify(_) -> false.

same(A, B) when byte_size(A) =:= byte_size(B) ->
    lists:foldl(fun({X, Y}, Acc) -> Acc bor (X bxor Y) end, 0, lists:zip(binary_to_list(A), binary_to_list(B))) =:= 0;
same(_, _) -> false.
