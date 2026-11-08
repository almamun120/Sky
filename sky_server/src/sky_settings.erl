%% Settings that can be changed while the server runs (from the admin web page). They are kept in a small JSON file
%% (config `settings_file`, default sky_settings.json in the directory where the server runs) and win over config/sys.config.
%% A key that is not in the file falls back to the value of config/sys.config, and then to the built-in default.
-module(sky_settings).
-export([init/0, get/2, all/0, put_many/1, schema/0]).

%% key => {type, built-in default}
schema() ->
    [{wire_formats, {formats, [msgpack, json]}},
     {ws_compress, {bool, false}},
     {registration_open, {bool, true}},
     {sms_text, {sms_text, "Your Sky verification code is ~s. Do not share it with anybody."}},
     {stun_urls, {urls_stun, []}},
     {turn_urls, {urls_turn, []}},
     {turn_secret, {secret, undefined}},
     {turn_ttl, {{int, 60, 604800}, 86400}}].

file_name() -> application:get_env(sky, settings_file, "sky_settings.json").

init() ->
    Over = case file:read_file(file_name()) of
        {ok, Bin} -> case catch jsx:decode(Bin, [return_maps]) of M when is_map(M) -> from_json(M); _ -> #{} end;
        _ -> #{}
    end,
    persistent_term:put({?MODULE, over}, Over),
    ok.

%% the value now in force
get(Key, Default) ->
    case maps:find(Key, persistent_term:get({?MODULE, over}, #{})) of
        {ok, V} -> V;
        error -> application:get_env(sky, Key, Default)
    end.

%% everything with where each value comes from (for the admin page)
all() ->
    Over = persistent_term:get({?MODULE, over}, #{}),
    maps:from_list([{Key, #{value => ?MODULE:get(Key, Def), set_here => maps:is_key(Key, Over)}} || {Key, {_, Def}} <- schema()]).

%% Map with binary keys (from the admin page). Returns ok | {error, Message}. Nothing is changed unless every value is valid.
put_many(In) ->
    try
        New = lists:foldl(fun({K, V}, Acc) ->
            case lists:keyfind(binary_to_existing_atom(K, utf8), 1, schema()) of
                {Atom, {Type, _}} -> Acc#{Atom => check(Type, V)};
                false -> throw({bad, <<"unknown setting ", K/binary>>})
            end end, #{}, maps:to_list(In)),
        Over = maps:merge(persistent_term:get({?MODULE, over}, #{}), New),
        ok = write(Over),
        persistent_term:put({?MODULE, over}, Over),
        ok
    catch
        throw:{bad, Msg} -> {error, Msg};
        error:badarg -> {error, <<"unknown setting">>};
        _:_ -> {error, <<"invalid values">>}
    end.

%% ---- validation of each type ----
check(bool, V) when is_boolean(V) -> V;
check(formats, L) when is_list(L), L =/= [] ->
    lists:usort([case X of <<"msgpack">> -> msgpack; <<"json">> -> json; _ -> throw({bad, <<"wire formats: msgpack and/or json">>}) end || X <- L]);
check(sms_text, B) when is_binary(B) ->
    S = unicode:characters_to_list(B),
    case length(S) =< 300 andalso length(string:split(S, "~s", all)) =:= 2 andalso not lists:member($\\, S) of
        true -> S;
        false -> throw({bad, <<"the SMS text must contain ~s exactly once (that is where the code goes) and be at most 300 characters">>})
    end;
check(urls_stun, L) -> urls(L, [<<"stun:">>, <<"stuns:">>]);
check(urls_turn, L) -> urls(L, [<<"turn:">>, <<"turns:">>]);
check(secret, V) when is_binary(V), byte_size(V) =< 200 -> V;
check({int, Lo, Hi}, V) when is_integer(V), V >= Lo, V =< Hi -> V;
check(_, _) -> throw({bad, <<"invalid value">>}).

urls(L, Prefixes) when is_list(L) ->
    [case is_binary(U) andalso byte_size(U) =< 200 andalso lists:any(fun(P) -> binary:longest_common_prefix([U, P]) =:= byte_size(P) end, Prefixes) of
         true -> U;
         false -> throw({bad, iolist_to_binary(["addresses must start with ", lists:join(" or ", Prefixes)])})
     end || U <- L];
urls(_, _) -> throw({bad, <<"a list of addresses is needed">>}).

%% ---- the file ----
to_json(Over) ->
    maps:from_list([{atom_to_binary(K, utf8), json_val(K, V)} || {K, V} <- maps:to_list(Over)]).
json_val(wire_formats, L) -> [atom_to_binary(X, utf8) || X <- L];
json_val(sms_text, S) -> unicode:characters_to_binary(S);
json_val(_, V) -> V.

from_json(M) ->
    maps:fold(fun(K, V, Acc) ->
        try
            Atom = binary_to_existing_atom(K, utf8),
            {Atom, {Type, _}} = lists:keyfind(Atom, 1, schema()),
            Acc#{Atom => check(Type, V)}
        catch _:_ -> Acc
        end end, #{}, M).

write(Over) ->
    File = file_name(),
    Tmp = File ++ ".tmp",
    ok = file:write_file(Tmp, jsx:prettify(jsx:encode(to_json(Over)))),
    ok = file:rename(Tmp, File).
