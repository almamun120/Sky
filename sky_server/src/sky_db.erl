%% Mnesia storage: users, login sessions, and undelivered items.
%% `pending` holds chat messages AND status receipts until the receiving
%% client confirms them.
-module(sky_db).
-export([init/0, put_user/2, user_exists/1,
         new_session/1, phone_for_token/1,
         put_pending/3, pending_for/1, ack/2,
         get_name/1, put_group/2, get_group/1, del_group/1,
         has_pending/1,
         del_session/1, purge_pending/1, purge_sessions/0,
         put_bundle/2, get_bundle/1, clear_prekeys/1, add_prekeys/2,
         take_prekey/1, prekey_count/1,
         set_last_seen/2, get_presence/1, set_share/2,
         block/2, unblock/2, is_blocked/2, blocks_of/1,
         put_report/4, list_reports/0,
         put_profile/3, get_profile/1,
         put_invite/2, get_invite/1, del_invite/1]).

-record(user, {phone, name}).
-define(SESSION_TTL, 2592000).   %% 30 days, in seconds
-record(session, {hash, phone, exp}).
-record(pending, {key, to, msg}).   %% key = {To, ItemId}
-record(keybundle, {phone, data}).   %% identity + signed/kyber prekeys (public parts only)
-record(otpk, {key, phone, pub}).     %% one-time prekeys, key = {Phone, Id}
-record(presence, {phone, last_seen = undefined, share = true}).  %% last_seen in ms; share = may others see it
-record(block, {key, blocker, blocked}).   %% key = {Blocker, Blocked}; Blocker no longer gets anything from Blocked
-record(invite, {code, gid}).             %% group invite link: code -> group id
-record(profile, {phone, blob, ts}).      %% blob = the owner's encrypted profile (about, photo); the server cannot read it
-record(report, {key, ts, data}).         %% key = {Reporter, Reported}; the newest report replaces the older one
-record(grp, {gid, data}).         %% data = #{<<"name">>, <<"owner">>, <<"members">>}

init() ->
    case mnesia:create_schema([node()]) of
        ok -> ok;
        {error, {_, {already_exists, _}}} -> ok
    end,
    ok = mnesia:start(),
    create(user, [{attributes, record_info(fields, user)},
                  {disc_copies, [node()]}]),
    create(session, [{attributes, record_info(fields, session)},
                     {disc_copies, [node()]}]),
    create(pending, [{attributes, record_info(fields, pending)},
                     {index, [to]},
                     {disc_copies, [node()]}]),
    create(grp, [{attributes, record_info(fields, grp)},
                 {disc_copies, [node()]}]),
    create(keybundle, [{attributes, record_info(fields, keybundle)},
                       {disc_copies, [node()]}]),
    create(otpk, [{attributes, record_info(fields, otpk)},
                  {index, [phone]},
                  {disc_copies, [node()]}]),
    create(presence, [{attributes, record_info(fields, presence)},
                      {disc_copies, [node()]}]),
    create(block, [{attributes, record_info(fields, block)},
                   {index, [blocker]},
                   {disc_copies, [node()]}]),
    create(report, [{attributes, record_info(fields, report)},
                    {disc_copies, [node()]}]),
    create(profile, [{attributes, record_info(fields, profile)},
                     {disc_copies, [node()]}]),
    create(invite, [{attributes, record_info(fields, invite)},
                    {disc_copies, [node()]}]),
    ok = mnesia:wait_for_tables([user, session, pending, grp, keybundle, otpk, presence, block, report, profile, invite], 10000),
    migrate_session(),
    drop_old_push_table().

create(Name, Opts) ->
    case mnesia:create_table(Name, Opts) of
        {atomic, ok} -> ok;
        {aborted, {already_exists, _}} -> ok
    end.

put_user(Phone, Name) ->
    mnesia:dirty_write(#user{phone = Phone, name = Name}).

user_exists(Phone) ->
    mnesia:dirty_read(user, Phone) =/= [].

%% Only the SHA-256 of the token is stored.
new_session(Phone) ->
    Token = binary:encode_hex(crypto:strong_rand_bytes(32)),
    ok = mnesia:dirty_write(#session{hash = crypto:hash(sha256, Token), phone = Phone,
                                     exp = now_s() + ?SESSION_TTL}),
    Token.

%% Sessions last 30 days and are extended while the user keeps using the app.
phone_for_token(Token) ->
    Hash = crypto:hash(sha256, Token),
    Now = now_s(),
    case mnesia:dirty_read(session, Hash) of
        [#session{phone = P, exp = Exp} = S] when Exp > Now ->
            case Exp - Now < ?SESSION_TTL div 2 of
                true -> mnesia:dirty_write(S#session{exp = Now + ?SESSION_TTL});
                false -> ok
            end,
            {ok, P};
        [#session{}] ->
            mnesia:dirty_delete(session, Hash),
            error;
        [] -> error
    end.

put_pending(To, Id, Msg) ->
    mnesia:dirty_write(#pending{key = {To, Id}, to = To, msg = Msg}).

pending_for(To) ->
    Rows = mnesia:dirty_index_read(pending, To, #pending.to),
    Msgs = [M || #pending{msg = M} <- Rows],
    lists:sort(fun(A, B) -> maps:get(<<"ts">>, A) =< maps:get(<<"ts">>, B) end, Msgs).

ack(To, Id) ->
    mnesia:dirty_delete(pending, {To, Id}).

get_name(Phone) ->
    case mnesia:dirty_read(user, Phone) of
        [#user{name = N}] -> N;
        [] -> undefined
    end.

put_group(Gid, Data) ->
    mnesia:dirty_write(#grp{gid = Gid, data = Data}).

del_group(Gid) -> mnesia:dirty_delete(grp, Gid).

get_group(Gid) ->
    case mnesia:dirty_read(grp, Gid) of
        [#grp{data = D}] -> {ok, D};
        [] -> error
    end.

has_pending(Phone) ->
    mnesia:dirty_index_read(pending, Phone, #pending.to) =/= [].

%% Step 4 used a table of FCM tokens; the own push service does not need it.
drop_old_push_table() ->
    case lists:member(push, mnesia:system_info(tables)) of
        true -> {atomic, ok} = mnesia:delete_table(push), ok;
        false -> ok
    end.

del_session(Token) ->
    mnesia:dirty_delete(session, crypto:hash(sha256, Token)).

%% Drop undelivered items (messages, receipts, group info) older than CutoffMs.
purge_pending(CutoffMs) ->
    lists:foreach(
      fun(K) ->
              case mnesia:dirty_read(pending, K) of
                  [#pending{msg = #{<<"ts">> := Ts}}] when Ts < CutoffMs ->
                      mnesia:dirty_delete(pending, K);
                  _ -> ok
              end
      end, mnesia:dirty_all_keys(pending)).

purge_sessions() ->
    Now = now_s(),
    lists:foreach(
      fun(K) ->
              case mnesia:dirty_read(session, K) of
                  [#session{exp = E}] when E < Now -> mnesia:dirty_delete(session, K);
                  _ -> ok
              end
      end, mnesia:dirty_all_keys(session)).

%% Sessions created before step 6 had no expiry field.
migrate_session() ->
    case mnesia:table_info(session, attributes) of
        [hash, phone] ->
            Exp = now_s() + ?SESSION_TTL,
            {atomic, ok} = mnesia:transform_table(
                             session, fun({session, H, P}) -> {session, H, P, Exp} end,
                             [hash, phone, exp]),
            ok;
        _ -> ok
    end.

now_s() -> os:system_time(second).

%% ---- end-to-end encryption: public key material only ----

put_bundle(Phone, Data) ->
    mnesia:dirty_write(#keybundle{phone = Phone, data = Data}).

get_bundle(Phone) ->
    case mnesia:dirty_read(keybundle, Phone) of
        [#keybundle{data = D}] -> {ok, D};
        [] -> error
    end.

clear_prekeys(Phone) ->
    lists:foreach(fun(#otpk{key = K}) -> mnesia:dirty_delete(otpk, K) end,
                  mnesia:dirty_index_read(otpk, Phone, #otpk.phone)).

add_prekeys(Phone, List) ->
    lists:foreach(fun(#{<<"id">> := Id, <<"pub">> := Pub}) ->
                          mnesia:dirty_write(#otpk{key = {Phone, Id}, phone = Phone, pub = Pub})
                  end, List).

%% Atomically hand out (and delete) one one-time prekey.
take_prekey(Phone) ->
    {atomic, R} = mnesia:transaction(
        fun() ->
                case mnesia:index_read(otpk, Phone, #otpk.phone) of
                    [#otpk{key = {_, Id} = K, pub = P} | _] ->
                        mnesia:delete({otpk, K}),
                        {ok, Id, P};
                    [] -> none
                end
        end),
    R.

prekey_count(Phone) ->
    length(mnesia:dirty_index_read(otpk, Phone, #otpk.phone)).

%% ---- presence: last seen and the "share my online status / last seen" privacy switch ----

set_last_seen(Phone, Ms) ->
    {_, Share} = get_presence(Phone),
    mnesia:dirty_write(#presence{phone = Phone, last_seen = Ms, share = Share}).

%% -> {LastSeenMs | undefined, Share :: boolean()}   (default: share = true)
get_presence(Phone) ->
    case mnesia:dirty_read(presence, Phone) of
        [#presence{last_seen = L, share = Sh}] -> {L, Sh};
        [] -> {undefined, true}
    end.

set_share(Phone, Share) when is_boolean(Share) ->
    {L, _} = get_presence(Phone),
    mnesia:dirty_write(#presence{phone = Phone, last_seen = L, share = Share}).

%% ---- block list and reports ----

-define(MAX_BLOCKS, 1000).

block(Blocker, Blocked) ->
    case length(blocks_of(Blocker)) >= ?MAX_BLOCKS andalso not is_blocked(Blocker, Blocked) of
        true -> {error, too_many_blocks};
        false -> mnesia:dirty_write(#block{key = {Blocker, Blocked}, blocker = Blocker, blocked = Blocked})
    end.

unblock(Blocker, Blocked) ->
    mnesia:dirty_delete(block, {Blocker, Blocked}).

%% Has Blocker blocked Who?
is_blocked(Blocker, Who) ->
    mnesia:dirty_read(block, {Blocker, Who}) =/= [].

blocks_of(Blocker) ->
    lists:sort([B || #block{blocked = B} <- mnesia:dirty_index_read(block, Blocker, #block.blocker)]).

%% Msgs: up to 5 maps #{<<"ts">>, <<"b">>} that the reporter chose to send along (their own phone
%% decrypted them; the server never could). Read them from a remote shell with sky_db:list_reports().
put_report(Reporter, Reported, Reason, Msgs) ->
    mnesia:dirty_write(#report{key = {Reporter, Reported}, ts = os:system_time(millisecond),
                               data = #{<<"reason">> => Reason, <<"msgs">> => Msgs}}).

list_reports() ->
    Rows = [mnesia:dirty_read(report, K) || K <- mnesia:dirty_all_keys(report)],
    lists:sort(fun(A, B) -> maps:get(ts, A) >= maps:get(ts, B) end,
               [#{reporter => R, reported => D, ts => Ts, data => Data}
                || [#report{key = {R, D}, ts = Ts, data = Data}] <- Rows]).

%% ---- encrypted profile blob (about text, photo id + photo key), see README step 18 ----

put_profile(Phone, Blob, Ts) ->
    mnesia:dirty_write(#profile{phone = Phone, blob = Blob, ts = Ts}).

get_profile(Phone) ->
    case mnesia:dirty_read(profile, Phone) of
        [#profile{blob = B, ts = Ts}] -> {ok, B, Ts};
        [] -> error
    end.

%% ---- group invite links ----

put_invite(Code, Gid) -> mnesia:dirty_write(#invite{code = Code, gid = Gid}).

get_invite(Code) ->
    case mnesia:dirty_read(invite, Code) of
        [#invite{gid = G}] -> {ok, G};
        [] -> error
    end.

del_invite(Code) -> mnesia:dirty_delete(invite, Code).
