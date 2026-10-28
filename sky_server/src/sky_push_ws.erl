%% The always-on push channel. Deliberately tiny: one small JSON frame in each direction.
%%
%%   client -> {"type":"hello","token":T}      server -> {"type":"hello_ok"}  (or error + close)
%%   client -> {"type":"ping"}                 server -> {"type":"pong"}       (keeps NAT/proxies open)
%%   server -> {"type":"wake","why":"msg"|"call"}
%%
%% If anything is already waiting for the phone at hello time, a wake is sent at once, so a phone
%% that was offline never misses what arrived in the meantime.
-module(sky_push_ws).
-behaviour(cowboy_websocket).
-export([init/2, websocket_init/1, websocket_handle/2, websocket_info/2]).

init(Req, _Opts) ->
    {cowboy_websocket, Req, #{phone => undefined},
     #{idle_timeout => 150000, max_frame_size => 2048}}.

websocket_init(S) ->
    erlang:send_after(20000, self(), hello_timeout),
    {[], S}.

websocket_handle({text, Json}, S) ->
    try jsx:decode(Json, [return_maps]) of
        Msg when is_map(Msg) -> handle(Msg, S);
        _ -> bad(S)
    catch _:_ -> bad(S)
    end;
websocket_handle(_Other, S) -> {[], S}.      %% ping/pong frames just reset the idle timer

websocket_info({wake, Why}, #{phone := P} = S) when P =/= undefined ->
    {[frame(#{<<"type">> => <<"wake">>, <<"why">> => atom_to_binary(Why)})], S};
websocket_info(replaced, S) -> {[{close, 1000, <<"replaced">>}], S};
websocket_info(logout, S) -> {[{close, 1000, <<"logout">>}], S};
websocket_info(hello_timeout, #{phone := undefined} = S) ->
    {[{close, 1008, <<"hello_timeout">>}], S};
websocket_info(_, S) -> {[], S}.

handle(#{<<"type">> := <<"hello">>, <<"token">> := Token}, #{phone := undefined} = S)
  when is_binary(Token), byte_size(Token) =< 128 ->
    case sky_db:phone_for_token(Token) of
        {ok, Phone} ->
            ok = sky_push:register(Phone, self()),
            Frames = [frame(#{<<"type">> => <<"hello_ok">>}) |
                      case sky_db:has_pending(Phone) of
                          true -> [frame(#{<<"type">> => <<"wake">>, <<"why">> => <<"msg">>})];
                          false ->
                              case sky_calls:waiting(Phone) of
                                  true -> [frame(#{<<"type">> => <<"wake">>, <<"why">> => <<"call">>})];
                                  false -> []
                              end
                      end],
            {Frames, S#{phone := Phone}};
        error ->
            {[frame(#{<<"type">> => <<"error">>, <<"code">> => <<"bad_token">>}),
              {close, 1008, <<"bad_token">>}], S}
    end;
handle(#{<<"type">> := <<"ping">>}, S) ->
    {[frame(#{<<"type">> => <<"pong">>})], S};
handle(_, S) -> bad(S).

bad(S) -> {[{close, 1003, <<"bad_request">>}], S}.

frame(Map) -> {text, jsx:encode(Map)}.
