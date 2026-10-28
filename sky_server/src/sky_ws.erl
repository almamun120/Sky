%% One WebSocket connection = one Erlang process. JSON text frames, see README.md.
-module(sky_ws).
-behaviour(cowboy_websocket).
-export([init/2, websocket_init/1, websocket_handle/2, websocket_info/2]).

-define(MAX_BODY, 4096).
-define(MAX_GROUP, 50).
-define(MAX_SDP_CT, 40000).  %% base64 of an encrypted SDP (video offers are large)
-define(MAX_CT, 16384).        %% base64 ciphertext of one message

init(Req, _Opts) ->
    {cowboy_websocket, Req,
     #{phone => undefined, bucket => {30.0, erlang:monotonic_time(millisecond)}},
     #{idle_timeout => 120000, max_frame_size => 524288}}.

websocket_init(S) ->
    erlang:send_after(30000, self(), auth_timeout),   %% must log in within 30 s
    {[], S}.

websocket_handle({text, Json}, S) ->
    try jsx:decode(Json, [return_maps]) of
        Msg when is_map(Msg) -> handle_limited(Msg, S);
        _ -> reply_err(<<"bad_request">>, S)
    catch _:_ -> reply_err(<<"bad_json">>, S)
    end;
websocket_handle(_Other, S) -> {[], S}.

websocket_info({deliver, Msg}, S) -> {[frame(Msg)], S};
websocket_info({presence_changed, P}, #{phone := Me} = S) when Me =/= undefined ->
    {[frame(presence_frame(Me, P))], S};
websocket_info(kicked, S) -> {[{close, 1000, <<"logged_in_elsewhere">>}], S};
websocket_info(auth_timeout, #{phone := undefined} = S) ->
    {[{close, 1008, <<"auth_timeout">>}], S};
websocket_info(_, S) -> {[], S}.

%% ---- pre-login ----

handle(#{<<"type">> := <<"request_otp">>, <<"phone">> := Phone}, #{phone := undefined} = S)
  when is_binary(Phone) ->
    case valid_phone(Phone) of
        false -> reply_err(<<"bad_phone">>, S);
        true ->
            case sky_otp:request(Phone) of
                {ok, Code} ->
                    Reply = case application:get_env(sky, dev_mode, true) of
                        true -> #{<<"type">> => <<"otp_sent">>, <<"dev_code">> => Code};
                        false -> ok = sky_sms:send(Phone, Code),
                                 #{<<"type">> => <<"otp_sent">>}
                    end,
                    {[frame(Reply)], S};
                {error, Why} -> reply_err(atom_to_binary(Why), S)
            end
    end;

handle(#{<<"type">> := <<"verify_otp">>, <<"phone">> := Phone, <<"code">> := Code,
         <<"name">> := Name}, #{phone := undefined} = S)
  when is_binary(Phone), is_binary(Code), is_binary(Name), byte_size(Name) =< 64 ->
    case valid_phone(Phone) andalso sky_otp:verify(Phone, Code) of
        ok ->
            ok = sky_db:put_user(Phone, Name),
            Token = sky_db:new_session(Phone),
            {Frames, S1} = do_login(Phone, S),
            {[frame(#{<<"type">> => <<"token">>, <<"token">> => Token}) | Frames], S1};
        false -> reply_err(<<"bad_phone">>, S);
        {error, Why} -> reply_err(atom_to_binary(Why), S)
    end;

handle(#{<<"type">> := <<"auth">>, <<"token">> := Token}, #{phone := undefined} = S)
  when is_binary(Token) ->
    case sky_db:phone_for_token(Token) of
        {ok, Phone} -> do_login(Phone, S);
        error -> reply_err(<<"bad_token">>, S)
    end;

%% ---- after login ----

%% ---- end-to-end encrypted messages: the server only sees opaque ciphertext ----
handle(#{<<"type">> := <<"upload_keys">>} = Req, #{phone := Me} = S) when Me =/= undefined ->
    case validate_upload(Req, Me) of
        {ok, Bundle, PreKeys} ->
            case Bundle of
                none -> ok;
                _ ->
                    ok = sky_db:put_bundle(Me, Bundle),
                    sky_db:clear_prekeys(Me)     %% new identity: old one-time keys are useless
            end,
            ok = sky_db:add_prekeys(Me, PreKeys),
            {[], S};
        {error, Code} -> reply_err(Code, S)
    end;

handle(#{<<"type">> := <<"fetch_keys">>, <<"phone">> := Phone}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(Phone) ->
    case sky_db:get_bundle(Phone) of
        error ->
            {[frame(#{<<"type">> => <<"error">>, <<"code">> => <<"no_keys">>,
                      <<"phone">> => Phone})], S};
        {ok, B} ->
            Base = B#{<<"type">> => <<"keys">>, <<"phone">> => Phone},
            Reply = case sky_db:take_prekey(Phone) of
                {ok, Id, Pub} -> Base#{<<"prekey">> => #{<<"id">> => Id, <<"pub">> => Pub}};
                none -> Base
            end,
            case sky_db:prekey_count(Phone) < 20 of
                true -> push_frame(Phone, #{<<"type">> => <<"prekeys_low">>,
                                            <<"count">> => sky_db:prekey_count(Phone)});
                false -> ok
            end,
            {[frame(Reply)], S}
    end;

handle(#{<<"type">> := <<"send">>, <<"id">> := Id, <<"to">> := To, <<"ct">> := Ct,
         <<"ctype">> := CT}, #{phone := From} = S)
  when From =/= undefined, is_binary(Id), byte_size(Id) =< 64, is_binary(To), is_binary(Ct),
       byte_size(Ct) =< ?MAX_CT, (CT =:= 2 orelse CT =:= 3) ->
    case sky_db:user_exists(To) of
        false -> err_id(<<"unknown_recipient">>, Id, S);
        true ->
            Ts = os:system_time(millisecond),
            Msg = #{<<"type">> => <<"msg">>, <<"id">> => Id, <<"from">> => From,
                    <<"name">> => sky_db:get_name(From), <<"ct">> => Ct,
                    <<"ctype">> => CT, <<"ts">> => Ts},
            ok = sky_db:put_pending(To, Id, Msg),
            push(To, Msg),
            {[frame(#{<<"type">> => <<"ack">>, <<"id">> => Id, <<"ts">> => Ts})], S}
    end;

%% One ciphertext per member (pairwise sessions), all in a single frame.
handle(#{<<"type">> := <<"send_group">>, <<"id">> := Id, <<"group">> := Gid, <<"cts">> := Cts},
       #{phone := From} = S)
  when From =/= undefined, is_binary(Id), byte_size(Id) =< 64, is_binary(Gid), is_map(Cts) ->
    case sky_db:get_group(Gid) of
        {ok, #{<<"members">> := Ms}} ->
            case lists:member(From, Ms) of
                false -> err_id(<<"not_member">>, Id, S);
                true ->
                    Others = Ms -- [From],
                    case [M || M <- Others, not valid_ct(maps:get(M, Cts, undefined))] of
                        [] ->
                            Ts = os:system_time(millisecond),
                            Name = sky_db:get_name(From),
                            lists:foreach(
                              fun(M) ->
                                      #{<<"t">> := T, <<"c">> := C} = maps:get(M, Cts),
                                      Msg = #{<<"type">> => <<"msg">>, <<"id">> => Id,
                                              <<"from">> => From, <<"name">> => Name,
                                              <<"group">> => Gid, <<"ct">> => C,
                                              <<"ctype">> => T, <<"ts">> => Ts},
                                      ok = sky_db:put_pending(M, Id, Msg),
                                      push(M, Msg)
                              end, Others),
                            {[frame(#{<<"type">> => <<"ack">>, <<"id">> => Id, <<"ts">> => Ts})], S};
                        Missing ->
                            {[frame(#{<<"type">> => <<"error">>, <<"code">> => <<"missing_ct">>,
                                      <<"id">> => Id, <<"members">> => Missing})], S}
                    end
            end;
        error -> err_id(<<"unknown_group">>, Id, S)
    end;

handle(#{<<"type">> := <<"send">>, <<"id">> := Id, <<"to">> := To, <<"body">> := Body} = Req,
       #{phone := From} = S)
  when From =/= undefined, is_binary(Id), is_binary(To), is_binary(Body),
       byte_size(Id) =< 64, byte_size(Body) =< ?MAX_BODY ->
    case sky_db:user_exists(To) of
        false ->
            {[frame(#{<<"type">> => <<"error">>, <<"code">> => <<"unknown_recipient">>,
                      <<"id">> => Id})], S};
        true ->
            Ts = os:system_time(millisecond),
            Msg = maps:merge(extra_fields(Req),
                    #{<<"type">> => <<"msg">>, <<"id">> => Id, <<"from">> => From,
                      <<"name">> => sky_db:get_name(From),
                      <<"body">> => Body, <<"ts">> => Ts}),
            ok = sky_db:put_pending(To, Id, Msg),
            push(To, Msg),
            {[frame(#{<<"type">> => <<"ack">>, <<"id">> => Id, <<"ts">> => Ts})], S}
    end;

%% ---- calls: the server only relays signalling ----
handle(#{<<"type">> := <<"call">>, <<"cid">> := Cid, <<"to">> := To} = Req, #{phone := From} = S)
  when From =/= undefined, is_binary(Cid), byte_size(Cid) =< 64, is_binary(To), To =/= From ->
    case sdp_fields(Req) of
        {error, Code} -> cid_err(Code, Cid, S);
        {ok, Sdp} ->
            case sky_db:user_exists(To) of
                false -> cid_err(<<"unknown_recipient">>, Cid, S);
                true ->
                    Frame = Sdp#{<<"type">> => <<"call_in">>, <<"cid">> => Cid, <<"from">> => From,
                                 <<"name">> => sky_db:get_name(From),
                                 <<"video">> => maps:get(<<"video">>, Req, false) =:= true},
                    State = case sky_router:lookup(To) of
                        {ok, Pid} -> Pid ! {deliver, Frame}, <<"ringing">>;
                        error -> sky_calls:hold(Cid, To, Frame), sky_push:notify(To, call), <<"calling">>
                    end,
                    {[frame(#{<<"type">> => <<"call_state">>, <<"cid">> => Cid,
                              <<"state">> => State})], S}
            end
    end;

handle(#{<<"type">> := T, <<"cid">> := Cid, <<"to">> := To} = Req, #{phone := From} = S)
  when From =/= undefined, is_binary(Cid), is_binary(To),
       (T =:= <<"call_answer">> orelse T =:= <<"call_ice">> orelse T =:= <<"call_end">>) ->
    Built = case T of
        <<"call_answer">> ->
            case sdp_fields(Req) of
                {ok, F} -> {ok, F#{<<"type">> => <<"call_answered">>}};
                {error, _} = E -> E
            end;
        <<"call_ice">> ->
            {ok, #{<<"type">> => <<"call_ice">>, <<"cand">> => map_or_empty(maps:get(<<"cand">>, Req, #{}))}};
        <<"call_end">> ->
            {ok, #{<<"type">> => <<"call_end">>,
                   <<"reason">> => bin_limit(maps:get(<<"reason">>, Req, <<>>), 32)}}
    end,
    case Built of
        {error, Code} -> cid_err(Code, Cid, S);
        {ok, Out} ->
            Frame = Out#{<<"cid">> => Cid, <<"from">> => From},
            case sky_router:lookup(To) of
                {ok, Pid} -> Pid ! {deliver, Frame};
                error ->
                    case T of
                        <<"call_ice">> -> sky_calls:append(Cid, Frame);
                        <<"call_end">> -> sky_calls:cancel(Cid);
                        _ -> ok
                    end
            end,
            {[], S}
    end;

%% ---- typing indicator: relayed to whoever is online, never stored ----
handle(#{<<"type">> := <<"typing">>, <<"on">> := On} = Req, #{phone := Me} = S)
  when Me =/= undefined, is_boolean(On) ->
    Frame = #{<<"type">> => <<"typing">>, <<"from">> => Me, <<"on">> => On},
    case Req of
        #{<<"group">> := G} when is_binary(G) ->
            case sky_db:get_group(G) of
                {ok, #{<<"members">> := Ms}} ->
                    case lists:member(Me, Ms) of
                        true -> [push_frame(M, Frame#{<<"group">> => G}) || M <- Ms -- [Me]], ok;
                        false -> ok
                    end;
                error -> ok
            end;
        #{<<"to">> := To} when is_binary(To), To =/= Me -> push_frame(To, Frame);
        _ -> ok
    end,
    {[], S};

%% ---- presence: online / last seen, with a reciprocal privacy switch ----
handle(#{<<"type">> := <<"presence">>, <<"phone">> := P}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(P) ->
    {[frame(presence_frame(Me, P))], S};

handle(#{<<"type">> := <<"watch">>, <<"phone">> := P}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(P) ->
    sky_router:watch(self(), P),
    {[frame(presence_frame(Me, P))], S};

handle(#{<<"type">> := <<"unwatch">>}, #{phone := Me} = S) when Me =/= undefined ->
    sky_router:watch(self(), undefined),
    {[], S};

handle(#{<<"type">> := <<"set_privacy">>, <<"presence">> := Share}, #{phone := Me} = S)
  when Me =/= undefined, is_boolean(Share) ->
    ok = sky_db:set_share(Me, Share),
    sky_router:changed(Me),
    {[frame(privacy_frame(Me))], S};

%% ---- own name and encrypted profile ----
%% The display name is public (it already travels with every message). About text and photo are
%% an opaque blob encrypted with a key that only the owner's chat partners receive (end-to-end).
handle(#{<<"type">> := <<"set_name">>, <<"name">> := Name}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(Name), byte_size(Name) =< 64 ->
    case string:trim(Name) of
        <<>> -> reply_err(<<"bad_name">>, S);
        T when is_binary(T) ->
            ok = sky_db:put_user(Me, T),
            {[frame(#{<<"type">> => <<"me">>, <<"name">> => T})], S};
        _ -> reply_err(<<"bad_name">>, S)
    end;

handle(#{<<"type">> := <<"set_profile">>, <<"blob">> := B}, #{phone := Me} = S)
  when Me =/= undefined ->
    case b64_ok(B, 8192) of
        true ->
            Ts = os:system_time(millisecond),
            ok = sky_db:put_profile(Me, B, Ts),
            {[frame(#{<<"type">> => <<"profile_saved">>, <<"ts">> => Ts})], S};
        false -> reply_err(<<"bad_request">>, S)
    end;

%% Blocked in either direction: the blob is withheld.
handle(#{<<"type">> := <<"get_profile">>, <<"phone">> := P}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(P) ->
    Base = #{<<"type">> => <<"profile_blob">>, <<"phone">> => P},
    Reply = case sky_db:is_blocked(P, Me) orelse sky_db:is_blocked(Me, P) of
        true -> Base#{<<"blob">> => null};
        false ->
            case sky_db:get_profile(P) of
                {ok, B, Ts} -> Base#{<<"blob">> => B, <<"ts">> => Ts};
                error -> Base#{<<"blob">> => null}
            end
    end,
    {[frame(Reply)], S};

%% ---- block / report ----
%% The server keeps the block list (so a blocked sender is dropped even while the blocker is offline)
%% and answers every change with the full list.
handle(#{<<"type">> := <<"block">>, <<"phone">> := P, <<"on">> := On}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(P), P =/= Me, is_boolean(On) ->
    case valid_phone(P) of
        false -> reply_err(<<"bad_phone">>, S);
        true ->
            case On andalso not sky_db:user_exists(P) of
                true -> reply_err(<<"unknown_recipient">>, S);
                false ->
                    Res = case On of
                        true -> sky_db:block(Me, P);
                        false -> sky_db:unblock(Me, P)
                    end,
                    sky_router:changed(Me),      %% whoever watches either of us re-reads the status
                    sky_router:changed(P),
                    case Res of
                        {error, too_many_blocks} ->
                            {[frame(#{<<"type">> => <<"error">>, <<"code">> => <<"too_many_blocks">>}),
                              frame(blocks_frame(Me))], S};
                        _ -> {[frame(blocks_frame(Me))], S}
                    end
            end
    end;

handle(#{<<"type">> := <<"report">>, <<"phone">> := P} = Req, #{phone := Me} = S)
  when Me =/= undefined, is_binary(P), P =/= Me ->
    case sky_db:user_exists(P) of
        false -> reply_err(<<"unknown_recipient">>, S);
        true ->
            Reason = bin_limit(maps:get(<<"reason">>, Req, <<>>), 64),
            ok = sky_db:put_report(Me, P, Reason, report_msgs(maps:get(<<"msgs">>, Req, []))),
            {[frame(#{<<"type">> => <<"reported">>, <<"phone">> => P})], S}
    end;

%% Log out: revoke this session and the push registration.
handle(#{<<"type">> := <<"logout">>, <<"token">> := Token}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(Token) ->
    case sky_db:phone_for_token(Token) of
        {ok, Me} ->
            sky_db:del_session(Token),
            sky_push:disconnect(Me);
        _ -> ok
    end,
    {[{close, 1000, <<"logout">>}], S};

%% Look up whether a number is registered (and its display name).
handle(#{<<"type">> := <<"lookup">>, <<"phone">> := Phone}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(Phone) ->
    Base = #{<<"type">> => <<"profile">>, <<"phone">> => Phone},
    Reply = case sky_db:get_name(Phone) of
        undefined -> Base#{<<"exists">> => false};
        Name -> Base#{<<"exists">> => true, <<"name">> => Name}
    end,
    {[frame(Reply)], S};

%% ---- group management (the owner adds/removes members; anyone can leave) ----
handle(#{<<"type">> := <<"add_members">>, <<"group">> := Gid, <<"members">> := New},
       #{phone := Me} = S)
  when Me =/= undefined, is_binary(Gid), is_list(New), length(New) >= 1, length(New) =< 49 ->
    case sky_db:get_group(Gid) of
        {ok, #{<<"members">> := Ms} = G} ->
            case not is_admin(G, Me) of
              true -> reply_err(<<"not_admin">>, S);
              false ->
            case lists:all(fun(P) -> is_binary(P) andalso valid_phone(P) andalso sky_db:user_exists(P) end, New) of
                false -> reply_err(<<"unknown_member">>, S);
                true ->
                    All = lists:usort(Ms ++ New),
                    case length(All) =< ?MAX_GROUP of
                        false -> reply_err(<<"group_full">>, S);
                        true ->
                            Added = All -- Ms,
                            lists:foreach(fun(P) -> sky_db:ack(P, <<"gx:", Gid/binary>>) end, Added),
                            ok = sky_db:put_group(Gid, G#{<<"members">> => All}),
                            lists:foreach(fun(M) -> send_group_info(M, Gid) end, All),
                            {[], S}
                    end
            end
            end;
        error -> reply_err(<<"unknown_group">>, S)
    end;

handle(#{<<"type">> := <<"remove_member">>, <<"group">> := Gid, <<"phone">> := Who},
       #{phone := Me} = S)
  when Me =/= undefined, is_binary(Gid), is_binary(Who), Who =/= Me ->
    case sky_db:get_group(Gid) of
        {ok, #{<<"owner">> := Owner, <<"members">> := Ms} = G} ->
            case lists:member(Who, Ms) of
                false -> reply_err(<<"not_member">>, S);
                true ->
                    %% the owner can remove anyone; other admins only people who are not admins
                    case Me =:= Owner orelse (is_admin(G, Me) andalso not is_admin(G, Who)) of
                        true -> drop_member(Gid, Who), {[], S};
                        false -> reply_err(<<"not_admin">>, S)
                    end
            end;
        error -> reply_err(<<"unknown_group">>, S)
    end;

%% ---- admins, "only admins can send", invite links (step 19) ----
handle(#{<<"type">> := <<"set_admin">>, <<"group">> := Gid, <<"phone">> := Who, <<"on">> := On},
       #{phone := Me} = S)
  when Me =/= undefined, is_binary(Gid), is_binary(Who), is_boolean(On) ->
    case sky_db:get_group(Gid) of
        {ok, #{<<"owner">> := Owner, <<"members">> := Ms} = G} ->
            case {is_admin(G, Me), lists:member(Who, Ms), Who =:= Owner} of
                {false, _, _} -> reply_err(<<"not_admin">>, S);
                {_, false, _} -> reply_err(<<"not_member">>, S);
                {_, _, true} -> reply_err(<<"bad_request">>, S);       %% the owner stays admin
                _ ->
                    A0 = maps:get(<<"admins">>, G, []),
                    A1 = case On of true -> lists:usort([Who | A0]); false -> A0 -- [Who] end,
                    ok = sky_db:put_group(Gid, G#{<<"admins">> => A1}),
                    lists:foreach(fun(M) -> send_group_info(M, Gid) end, Ms),
                    {[], S}
            end;
        error -> reply_err(<<"unknown_group">>, S)
    end;

handle(#{<<"type">> := <<"group_settings">>, <<"group">> := Gid, <<"announce">> := On},
       #{phone := Me} = S)
  when Me =/= undefined, is_binary(Gid), is_boolean(On) ->
    case sky_db:get_group(Gid) of
        {ok, #{<<"members">> := Ms} = G} ->
            case is_admin(G, Me) of
                false -> reply_err(<<"not_admin">>, S);
                true ->
                    ok = sky_db:put_group(Gid, G#{<<"announce">> => On}),
                    lists:foreach(fun(M) -> send_group_info(M, Gid) end, Ms),
                    {[], S}
            end;
        error -> reply_err(<<"unknown_group">>, S)
    end;

%% An admin gets the group's invite code (the same one every time, until `reset` is true).
handle(#{<<"type">> := <<"invite_create">>, <<"group">> := Gid} = Req, #{phone := Me} = S)
  when Me =/= undefined, is_binary(Gid) ->
    case sky_db:get_group(Gid) of
        {ok, G} ->
            case is_admin(G, Me) of
                false -> reply_err(<<"not_admin">>, S);
                true ->
                    Old = maps:get(<<"invite">>, G, undefined),
                    Code = case is_binary(Old) andalso maps:get(<<"reset">>, Req, false) =/= true of
                        true -> Old;
                        false ->
                            case Old of undefined -> ok; _ -> sky_db:del_invite(Old) end,
                            New = binary:encode_hex(crypto:strong_rand_bytes(12)),
                            ok = sky_db:put_invite(New, Gid),
                            ok = sky_db:put_group(Gid, G#{<<"invite">> => New}),
                            New
                    end,
                    {[frame(#{<<"type">> => <<"invite">>, <<"group">> => Gid, <<"code">> => Code})], S}
            end;
        error -> reply_err(<<"unknown_group">>, S)
    end;

handle(#{<<"type">> := <<"join_invite">>, <<"code">> := Code0}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(Code0), byte_size(Code0) =< 64 ->
    Code = string:uppercase(Code0),
    case sky_db:get_invite(Code) of
        error -> reply_err(<<"bad_invite">>, S);
        {ok, Gid} ->
            case sky_db:get_group(Gid) of
                error -> reply_err(<<"bad_invite">>, S);
                {ok, #{<<"members">> := Ms} = G} ->
                    case lists:member(Me, Ms) of
                        true -> send_group_info(Me, Gid), {[], S};      %% already in: just refresh
                        false when length(Ms) >= ?MAX_GROUP -> reply_err(<<"group_full">>, S);
                        false ->
                            All = lists:usort([Me | Ms]),
                            sky_db:ack(Me, <<"gx:", Gid/binary>>),
                            ok = sky_db:put_group(Gid, G#{<<"members">> => All}),
                            lists:foreach(fun(M) -> send_group_info(M, Gid) end, All),
                            {[], S}
                    end
            end
    end;

handle(#{<<"type">> := <<"leave_group">>, <<"group">> := Gid}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(Gid) ->
    case sky_db:get_group(Gid) of
        {ok, #{<<"members">> := Ms}} ->
            case lists:member(Me, Ms) of
                false -> reply_err(<<"not_member">>, S);
                true -> drop_member(Gid, Me), {[], S}
            end;
        error -> reply_err(<<"unknown_group">>, S)
    end;

%% Group chat: the creator is always a member.
handle(#{<<"type">> := <<"create_group">>, <<"name">> := Name, <<"members">> := Members},
       #{phone := Me} = S)
  when Me =/= undefined, is_binary(Name), byte_size(Name) >= 1, byte_size(Name) =< 64,
       is_list(Members), length(Members) =< 49 ->
    case lists:all(fun is_binary/1, Members) of
        false -> reply_err(<<"bad_request">>, S);
        true ->
            All = lists:usort([Me | Members]),
            case length(All) >= 2 andalso
                 lists:all(fun(P) -> valid_phone(P) andalso sky_db:user_exists(P) end, All) of
                false -> reply_err(<<"unknown_member">>, S);
                true ->
                    Gid = binary:encode_hex(crypto:strong_rand_bytes(8)),
                    ok = sky_db:put_group(Gid, #{<<"name">> => Name,
                                                      <<"owner">> => Me,
                                                      <<"members">> => All}),
                    lists:foreach(fun(M) -> send_group_info(M, Gid) end, All),
                    {[], S}
            end
    end;

handle(#{<<"type">> := <<"send_group">>, <<"id">> := Id, <<"group">> := Gid,
         <<"body">> := Body} = Req, #{phone := From} = S)
  when From =/= undefined, is_binary(Id), is_binary(Gid), is_binary(Body),
       byte_size(Id) =< 64, byte_size(Body) =< ?MAX_BODY ->
    case sky_db:get_group(Gid) of
        {ok, #{<<"members">> := Ms}} ->
            case lists:member(From, Ms) of
                false -> err_id(<<"not_member">>, Id, S);
                true ->
                    Ts = os:system_time(millisecond),
                    Msg = maps:merge(extra_fields(Req),
                            #{<<"type">> => <<"msg">>, <<"id">> => Id, <<"from">> => From,
                              <<"name">> => sky_db:get_name(From), <<"group">> => Gid,
                              <<"body">> => Body, <<"ts">> => Ts}),
                    lists:foreach(fun(M) ->
                                          ok = sky_db:put_pending(M, Id, Msg),
                                          push(M, Msg)
                                  end, Ms -- [From]),
                    {[frame(#{<<"type">> => <<"ack">>, <<"id">> => Id, <<"ts">> => Ts})], S}
            end;
        error -> err_id(<<"unknown_group">>, Id, S)
    end;

%% Group messages: each member's receipt goes back to the sender (with the group id), so the
%% sender can show "delivered / read" once every member has it.
handle(#{<<"type">> := Type, <<"id">> := Id, <<"group">> := Gid} = Req, #{phone := Me} = S)
  when Me =/= undefined, (Type =:= <<"delivered">> orelse Type =:= <<"read">>),
       is_binary(Id), is_binary(Gid) ->
    sky_db:ack(Me, Id),
    case maps:get(<<"from">>, Req, undefined) of
        Sender when is_binary(Sender), Sender =/= Me ->
            case sky_db:get_group(Gid) of
                {ok, #{<<"members">> := Ms}} ->
                    case lists:member(Me, Ms) andalso lists:member(Sender, Ms) of
                        true -> send_status(Sender, Id, Type, Me, Gid);
                        false -> ok
                    end;
                error -> ok
            end;
        _ -> ok
    end,
    {[], S};

%% Recipient confirms a message: delivered, or read (blue ticks).
handle(#{<<"type">> := Type, <<"id">> := Id, <<"from">> := Sender}, #{phone := Me} = S)
  when Me =/= undefined, (Type =:= <<"delivered">> orelse Type =:= <<"read">>),
       is_binary(Id), is_binary(Sender) ->
    sky_db:ack(Me, Id),
    case sky_db:user_exists(Sender) of
        true -> send_status(Sender, Id, Type, Me);
        false -> ok
    end,
    {[], S};

%% Client confirms it stored a status receipt.
handle(#{<<"type">> := <<"got">>, <<"pid">> := Pid}, #{phone := Me} = S)
  when Me =/= undefined, is_binary(Pid) ->
    sky_db:ack(Me, Pid),
    {[], S};

handle(_, #{phone := undefined} = S) -> reply_err(<<"login_required">>, S);
handle(_, S) -> reply_err(<<"bad_request">>, S).

%% ---- helpers ----

do_login(Phone, S) ->
    ok = sky_router:register(Phone, self()),
    Pending = [frame(M) || M <- sky_db:pending_for(Phone)],
    Calls = [frame(F) || F <- sky_calls:take(Phone)],     %% a call that rang while offline
    LoginOk = frame(#{<<"type">> => <<"login_ok">>, <<"ice">> => ice_servers(Phone)}),
    KeyFrames = case sky_db:get_bundle(Phone) of
        error -> [frame(#{<<"type">> => <<"keys_needed">>})];
        {ok, _} ->
            case sky_db:prekey_count(Phone) of
                N when N < 20 -> [frame(#{<<"type">> => <<"prekeys_low">>, <<"count">> => N})];
                _ -> []
            end
    end,
    {[LoginOk, frame(privacy_frame(Phone)), frame(blocks_frame(Phone)) | KeyFrames ++ Pending ++ Calls],
     S#{phone := Phone}}.

blocks_frame(Phone) ->
    #{<<"type">> => <<"blocks">>, <<"phones">> => sky_db:blocks_of(Phone)}.

%% At most 5 messages, each cut to 1000 bytes, as sent by the reporter's own phone.
report_msgs(L) when is_list(L) ->
    [#{<<"ts">> => Ts, <<"b">> => B}
     || #{<<"ts">> := Ts, <<"b">> := B} <- lists:sublist(L, 5),
        is_integer(Ts), is_binary(B), byte_size(B) =< 1000];
report_msgs(_) -> [].

privacy_frame(Phone) ->
    {_, Share} = sky_db:get_presence(Phone),
    #{<<"type">> => <<"privacy">>, <<"presence">> => Share}.

%% Online / last seen of Target as seen by Me. Reciprocal: if either side hides it, nobody sees it.
presence_frame(Me, Target) ->
    Base = #{<<"type">> => <<"presence">>, <<"phone">> => Target},
    {LastSeen, TheirShare} = sky_db:get_presence(Target),
    {_, MyShare} = sky_db:get_presence(Me),
    case sky_db:user_exists(Target) andalso TheirShare andalso MyShare andalso
         not sky_db:is_blocked(Target, Me) andalso not sky_db:is_blocked(Me, Target) of
        false -> Base#{<<"hidden">> => true};
        true ->
            case sky_router:lookup(Target) of
                {ok, _} -> Base#{<<"online">> => true};
                error when LastSeen =:= undefined -> Base#{<<"online">> => false};
                error -> Base#{<<"online">> => false, <<"last_seen">> => LastSeen}
            end
    end.

%% Receipts are stored like messages, so offline senders get them on next login.
send_status(To, Id, Status, By) -> send_status(To, Id, Status, By, undefined).

send_status(To, Id, Status, By, Group) ->
    Pid = <<"st:", Status/binary, ":", Id/binary, ":", By/binary>>,   %% By: one per group member
    Base = #{<<"type">> => <<"status">>, <<"id">> => Id, <<"status">> => Status,
             <<"by">> => By, <<"pid">> => Pid, <<"ts">> => os:system_time(millisecond)},
    Msg = case Group of undefined -> Base; _ -> Base#{<<"group">> => Group} end,
    ok = sky_db:put_pending(To, Pid, Msg),
    push(To, Msg).

%% Remove one member. The removed person is told (group_removed, kept until they confirm);
%% the others get the new member list. If the owner leaves, the next member becomes owner;
%% a group with a single member left is deleted.
drop_member(Gid, Who) ->
    {ok, #{<<"owner">> := Owner, <<"members">> := Ms} = G} = sky_db:get_group(Gid),
    Rest = Ms -- [Who],
    Pid = <<"gx:", Gid/binary>>,
    Gone = #{<<"type">> => <<"group_removed">>, <<"gid">> => Gid, <<"pid">> => Pid,
             <<"ts">> => os:system_time(millisecond)},
    ok = sky_db:put_pending(Who, Pid, Gone),
    push_frame(Who, Gone),
    case Rest of
        [_, _ | _] ->
            Admins = [A || A <- maps:get(<<"admins">>, G, []), A =/= Who, lists:member(A, Rest)],
            NewOwner = case Owner =:= Who of
                true -> case Admins of [A | _] -> A; [] -> hd(Rest) end;      %% an admin first
                false -> Owner
            end,
            ok = sky_db:put_group(Gid, G#{<<"owner">> => NewOwner, <<"members">> => Rest,
                                          <<"admins">> => Admins -- [NewOwner]}),
            lists:foreach(fun(M) -> send_group_info(M, Gid) end, Rest);
        _ ->
            %% nobody to talk to any more
            lists:foreach(fun(M) ->
                                  P = <<"gx:", Gid/binary>>,
                                  Frame = Gone#{<<"ts">> => os:system_time(millisecond)},
                                  ok = sky_db:put_pending(M, P, Frame),
                                  push_frame(M, Frame)
                          end, Rest),
            case maps:get(<<"invite">>, G, undefined) of
                undefined -> ok;
                Code -> sky_db:del_invite(Code)
            end,
            ok = sky_db:del_group(Gid)
    end.

send_group_info(Member, Gid) ->
    {ok, G} = sky_db:get_group(Gid),
    Members = [#{<<"phone">> => P, <<"name">> => sky_db:get_name(P)}
               || P <- maps:get(<<"members">>, G)],
    Pid = <<"g:", Gid/binary>>,
    Msg = #{<<"type">> => <<"group">>, <<"gid">> => Gid, <<"name">> => maps:get(<<"name">>, G),
            <<"owner">> => maps:get(<<"owner">>, G), <<"members">> => Members,
            <<"admins">> => admins_of(G), <<"announce">> => maps:get(<<"announce">>, G, false),
            <<"pid">> => Pid, <<"ts">> => os:system_time(millisecond)},
    ok = sky_db:put_pending(Member, Pid, Msg),
    push(Member, Msg).

%% The offer/answer (SDP carries the DTLS fingerprint of the media) travels inside the Signal session
%% as sct/sctype, so the server cannot read or swap it. Plain `sdp` is accepted only if require_e2e=false.
sdp_fields(#{<<"sct">> := C, <<"sctype">> := T}) when is_binary(C), (T =:= 2 orelse T =:= 3) ->
    case b64_ok(C, ?MAX_SDP_CT) of
        true -> {ok, #{<<"sct">> => C, <<"sctype">> => T}};
        false -> {error, <<"bad_request">>}
    end;
sdp_fields(#{<<"sdp">> := Sdp}) when is_binary(Sdp), byte_size(Sdp) =< 20000 ->
    case e2e_required() of
        true -> {error, <<"e2e_required">>};
        false -> {ok, #{<<"sdp">> => Sdp}}
    end;
sdp_fields(_) -> {error, <<"bad_request">>}.

cid_err(Code, Cid, S) ->
    {[frame(#{<<"type">> => <<"error">>, <<"code">> => Code, <<"cid">> => Cid})], S}.

err_id(Code, Id, S) ->
    {[frame(#{<<"type">> => <<"error">>, <<"code">> => Code, <<"id">> => Id})], S}.

push(To, Msg) ->
    case sky_router:lookup(To) of
        {ok, Pid} -> Pid ! {deliver, Msg};
        error ->
            %% Offline: wake the device over its own push channel (no content is sent).
            case maps:get(<<"type">>, Msg) of
                <<"msg">> -> sky_push:notify(To, msg);
                _ -> ok
            end
    end.

%% Optional attachment fields (the file itself is uploaded over HTTP first).
extra_fields(M) ->
    case maps:get(<<"media">>, M, undefined) of
        Id when is_binary(Id), byte_size(Id) =:= 32 ->
            Kind = case maps:get(<<"kind">>, M, <<"file">>) of
                K when K =:= <<"image">>; K =:= <<"audio">>; K =:= <<"file">> -> K;
                _ -> <<"file">>
            end,
            #{<<"media">> => Id, <<"kind">> => Kind,
              <<"fname">> => bin_limit(maps:get(<<"fname">>, M, <<>>), 128),
              <<"mime">> => bin_limit(maps:get(<<"mime">>, M, <<>>), 100),
              <<"size">> => int_field(M, <<"size">>),
              <<"dur">> => int_field(M, <<"dur">>)};
        _ -> #{}
    end.

bin_limit(B, N) when is_binary(B), byte_size(B) =< N -> B;
bin_limit(_, _) -> <<>>.

int_field(M, Key) ->
    case maps:get(Key, M, 0) of
        N when is_integer(N), N >= 0, N =< 100000000 -> N;
        _ -> 0
    end.

%% Token bucket per connection: burst 30, refills 5 per second.
handle_limited(Msg, S) ->
    case silently_dropped(Msg, S) of
        {drop, Out} -> Out;
        pass -> handle_limited1(Msg, S)
    end.

%% A person who was blocked is never told: their messages are acknowledged but not delivered,
%% their calls just ring, their typing is not relayed. The blocker's own traffic is unaffected.
silently_dropped(#{<<"type">> := <<"send">>, <<"id">> := Id, <<"to">> := To}, #{phone := From} = S)
  when is_binary(From), is_binary(Id), is_binary(To) ->
    case sky_db:is_blocked(To, From) of
        true -> {drop, {[frame(#{<<"type">> => <<"ack">>, <<"id">> => Id,
                                 <<"ts">> => os:system_time(millisecond)})], S}};
        false -> pass
    end;
silently_dropped(#{<<"type">> := <<"call">>, <<"cid">> := Cid, <<"to">> := To}, #{phone := From} = S)
  when is_binary(From), is_binary(Cid), is_binary(To) ->
    case sky_db:is_blocked(To, From) of
        true -> {drop, {[frame(#{<<"type">> => <<"call_state">>, <<"cid">> => Cid,
                                 <<"state">> => <<"ringing">>})], S}};
        false -> pass
    end;
silently_dropped(#{<<"type">> := T, <<"to">> := To}, #{phone := From} = S)
  when is_binary(From), is_binary(To),
       (T =:= <<"typing">> orelse T =:= <<"call_answer">> orelse T =:= <<"call_ice">>
        orelse T =:= <<"call_end">>) ->
    case sky_db:is_blocked(To, From) of
        true -> {drop, {[], S}};
        false -> pass
    end;
%% Groups set to "only admins can send": a member's message (or reaction, edit, delete, which look the same
%% to the server) is refused with a visible error.
silently_dropped(#{<<"type">> := <<"send_group">>, <<"id">> := Id, <<"group">> := Gid}, #{phone := From} = S)
  when is_binary(From), is_binary(Id), is_binary(Gid) ->
    case sky_db:get_group(Gid) of
        {ok, #{<<"members">> := Ms} = G} ->
            case lists:member(From, Ms) andalso maps:get(<<"announce">>, G, false) =:= true
                 andalso not is_admin(G, From) of
                true -> {drop, err_id(<<"admins_only">>, Id, S)};
                false -> pass
            end;
        _ -> pass
    end;
silently_dropped(_, _) -> pass.

%% The owner is always an admin; `admins` lists the others (missing in groups made before step 19).
admins_of(G) -> lists:usort([maps:get(<<"owner">>, G) | maps:get(<<"admins">>, G, [])]).
is_admin(G, P) -> lists:member(P, admins_of(G)).

handle_limited1(Msg, S) ->
    case plaintext_blocked(Msg) of
        true ->
            Id = maps:get(<<"id">>, Msg, undefined),
            Err = #{<<"type">> => <<"error">>, <<"code">> => <<"e2e_required">>},
            {[frame(case is_binary(Id) of true -> Err#{<<"id">> => Id}; false -> Err end)], S};
        false -> rate_limited(Msg, S)
    end.

rate_limited(Msg, S) ->
    case needs_limit(Msg) of
        false -> handle(Msg, S);
        true ->
            case take_token(S) of
                {ok, S1} -> handle(Msg, S1);
                limited ->
                    Err = #{<<"type">> => <<"error">>, <<"code">> => <<"rate_limited">>},
                    Err1 = case maps:get(<<"id">>, Msg, undefined) of
                        Id when is_binary(Id) -> Err#{<<"id">> => Id};
                        _ -> Err
                    end,
                    {[frame(Err1)], S}
            end
    end.

needs_limit(#{<<"type">> := T}) ->
    lists:member(T, [<<"send">>, <<"send_group">>, <<"lookup">>, <<"create_group">>, <<"add_members">>, <<"remove_member">>,
                     <<"request_otp">>, <<"verify_otp">>, <<"call">>, <<"fetch_keys">>,
                     <<"presence">>, <<"watch">>, <<"set_privacy">>, <<"block">>, <<"report">>,
                     <<"set_name">>, <<"set_profile">>, <<"get_profile">>,
                     <<"set_admin">>, <<"group_settings">>, <<"invite_create">>, <<"join_invite">>]);
needs_limit(_) -> false.

take_token(#{bucket := {Tok0, Last}} = S) ->
    Now = erlang:monotonic_time(millisecond),
    Tok = min(30.0, Tok0 + (Now - Last) / 1000 * 5),
    case Tok >= 1.0 of
        true -> {ok, S#{bucket := {Tok - 1.0, Now}}};
        false -> limited
    end.

%% STUN always; TURN (coturn "use-auth-secret") when turn_urls + turn_secret are configured.
%% coturn also answers STUN, so with your own coturn nothing here depends on a third party:
%% unless stun_urls is set, STUN addresses are derived from turn_urls (turn:/turns: -> stun:).
%% Only with neither configured does it fall back to a public STUN server (development).
ice_servers(Phone) ->
    Turn = application:get_env(sky, turn_urls, undefined),
    Stun = case application:get_env(sky, stun_urls, undefined) of
        L when is_list(L), L =/= [] -> L;
        _ when is_list(Turn), Turn =/= [] -> lists:usort([stun_from(U) || U <- Turn]);
        _ -> [<<"stun:stun.l.google.com:19302">>]
    end,
    Base = [#{<<"urls">> => Stun}],
    case {Turn, application:get_env(sky, turn_secret, undefined)} of
        {Urls, Secret} when is_list(Urls), (is_binary(Secret) orelse is_list(Secret)) ->
            User = <<(integer_to_binary(os:system_time(second) + 86400))/binary, ":", Phone/binary>>,
            Cred = base64:encode(crypto:mac(hmac, sha, Secret, User)),
            Base ++ [#{<<"urls">> => Urls, <<"username">> => User, <<"credential">> => Cred}];
        _ -> Base
    end.

stun_from(<<"turns:", Rest/binary>>) -> <<"stun:", (strip_port(Rest))/binary, ":3478">>;
stun_from(<<"turn:", Rest/binary>>) -> <<"stun:", (strip_port(Rest))/binary, ":3478">>;
stun_from(Other) -> Other.

%% "host:5349?transport=tcp" -> "host"
strip_port(B) ->
    [NoQuery | _] = binary:split(B, <<"?">>),
    [Host | _] = binary:split(NoQuery, <<":">>),
    Host.

map_or_empty(M) when is_map(M) -> M;
map_or_empty(_) -> #{}.

%% With require_e2e (default true) the server refuses plaintext chat messages.
e2e_required() -> application:get_env(sky, require_e2e, true).

plaintext_blocked(#{<<"type">> := <<"send">>} = M) ->
    not maps:is_key(<<"ct">>, M) andalso e2e_required();
plaintext_blocked(#{<<"type">> := <<"send_group">>} = M) ->
    not maps:is_key(<<"cts">>, M) andalso e2e_required();
plaintext_blocked(_) -> false.

valid_ct(#{<<"t">> := T, <<"c">> := C})
  when (T =:= 2 orelse T =:= 3), is_binary(C), byte_size(C) =< ?MAX_CT -> true;
valid_ct(_) -> false.

%% Key upload: either a full bundle (identity + signed prekey + kyber prekey, optionally with
%% one-time prekeys) or only a top-up of one-time prekeys.
validate_upload(Req, Me) ->
    PreKeys = maps:get(<<"prekeys">>, Req, []),
    Bundle = case maps:is_key(<<"identity">>, Req) of
        false -> none;
        true ->
            Id = maps:get(<<"identity">>, Req, undefined),
            Reg = maps:get(<<"reg">>, Req, undefined),
            Spk = maps:get(<<"spk">>, Req, undefined),
            Kyber = maps:get(<<"kyber">>, Req, undefined),
            case b64_ok(Id, 128) andalso is_integer(Reg) andalso signed_ok(Spk) andalso signed_ok(Kyber) of
                true -> #{<<"identity">> => Id, <<"reg">> => Reg, <<"spk">> => Spk, <<"kyber">> => Kyber};
                false -> error
            end
    end,
    case {Bundle, is_list(PreKeys) andalso length(PreKeys) =< 200 andalso lists:all(fun prekey_ok/1, PreKeys)} of
        {error, _} -> {error, <<"bad_keys">>};
        {_, false} -> {error, <<"bad_keys">>};
        {_, true} ->
            case sky_db:prekey_count(Me) + length(PreKeys) > 500 of
                true -> {error, <<"too_many_keys">>};
                false -> {ok, Bundle, PreKeys}
            end
    end.

signed_ok(#{<<"id">> := Id, <<"pub">> := Pub, <<"sig">> := Sig}) ->
    is_integer(Id) andalso b64_ok(Pub, 4096) andalso b64_ok(Sig, 256);
signed_ok(_) -> false.

prekey_ok(#{<<"id">> := Id, <<"pub">> := Pub}) ->
    is_integer(Id) andalso Id >= 0 andalso b64_ok(Pub, 128);
prekey_ok(_) -> false.

b64_ok(B, Max) when is_binary(B), byte_size(B) =< Max, byte_size(B) > 0 ->
    try base64:decode(B), true catch _:_ -> false end;
b64_ok(_, _) -> false.

%% Send a frame to a user if they are online (no push wake).
push_frame(Phone, Frame) ->
    case sky_router:lookup(Phone) of
        {ok, Pid} -> Pid ! {deliver, Frame}, ok;
        error -> ok
    end.

frame(Map) -> {text, jsx:encode(Map)}.

reply_err(Code, S) ->
    {[frame(#{<<"type">> => <<"error">>, <<"code">> => Code})], S}.

valid_phone(<<"+", Digits/binary>>) ->
    byte_size(Digits) >= 7 andalso byte_size(Digits) =< 15 andalso
        lists:all(fun(C) -> C >= $0 andalso C =< $9 end, binary_to_list(Digits));
valid_phone(_) -> false.
