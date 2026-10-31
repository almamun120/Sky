%% Minimal MessagePack (https://github.com/msgpack/msgpack/blob/master/spec.md) for the /ws wire format.
%%
%% The rest of the server works with maps whose binary values (ciphertext, keys) are base64 strings, as in the
%% JSON format. This module converts at the edge:
%%   decode/1  : MessagePack -> map; every "bin" value becomes a base64 string.
%%   encode/2  : map -> MessagePack; values of the given keys become "bin" when they are canonical base64
%%               (otherwise they stay strings, so nothing is ever altered).
%% Supported: nil, bool, ints (up to 64 bit), float 32/64, str, bin, array, map with string keys.
%% Not supported (decode fails): ext types, non-string map keys, invalid UTF-8, nesting deeper than 64.
-module(sky_msgpack).
-export([encode/2, decode/1]).

-define(MAX_DEPTH, 64).

%% ---------------- encode ----------------

-spec encode(term(), [binary()]) -> binary().
encode(Term, BinKeys) -> iolist_to_binary(enc(Term, BinKeys)).

enc(null, _) -> <<16#c0>>;
enc(false, _) -> <<16#c2>>;
enc(true, _) -> <<16#c3>>;
enc(A, K) when is_atom(A) -> enc(atom_to_binary(A, utf8), K);
enc(I, _) when is_integer(I), I >= 0 ->
    if I =< 16#7f -> <<I>>;
       I =< 16#ff -> <<16#cc, I>>;
       I =< 16#ffff -> <<16#cd, I:16>>;
       I =< 16#ffffffff -> <<16#ce, I:32>>;
       true -> <<16#cf, I:64>>
    end;
enc(I, _) when is_integer(I) ->
    if I >= -32 -> <<I:8/signed>>;
       I >= -16#80 -> <<16#d0, I:8/signed>>;
       I >= -16#8000 -> <<16#d1, I:16/signed>>;
       I >= -16#80000000 -> <<16#d2, I:32/signed>>;
       true -> <<16#d3, I:64/signed>>
    end;
enc(F, _) when is_float(F) -> <<16#cb, F:64/float>>;
enc(B, _) when is_binary(B) -> enc_str(B);
enc(L, K) when is_list(L) ->
    N = length(L),
    [if N < 16 -> <<(16#90 bor N)>>;
        N < 65536 -> <<16#dc, N:16>>;
        true -> <<16#dd, N:32>>
     end | [enc(E, K) || E <- L]];
enc(M, K) when is_map(M) ->
    N = map_size(M),
    [if N < 16 -> <<(16#80 bor N)>>;
        N < 65536 -> <<16#de, N:16>>;
        true -> <<16#df, N:32>>
     end | [[enc_str(key(Key)), enc_val(key(Key), V, K)] || {Key, V} <- maps:to_list(M)]].

enc_val(Key, V, BinKeys) when is_binary(V) ->
    case lists:member(Key, BinKeys) of
        true ->
            case canonical_b64(V) of
                {ok, Raw} -> enc_bin(Raw);
                error -> enc_str(V)
            end;
        false -> enc_str(V)
    end;
enc_val(_, V, BinKeys) -> enc(V, BinKeys).

key(K) when is_binary(K) -> K;
key(K) when is_atom(K) -> atom_to_binary(K, utf8).

canonical_b64(B) ->
    try base64:decode(B) of
        Raw -> case base64:encode(Raw) of B -> {ok, Raw}; _ -> error end
    catch _:_ -> error
    end.

enc_str(B) ->
    N = byte_size(B),
    [if N < 32 -> <<(16#a0 bor N)>>;
        N < 256 -> <<16#d9, N>>;
        N < 65536 -> <<16#da, N:16>>;
        true -> <<16#db, N:32>>
     end, B].

enc_bin(B) ->
    N = byte_size(B),
    [if N < 256 -> <<16#c4, N>>;
        N < 65536 -> <<16#c5, N:16>>;
        true -> <<16#c6, N:32>>
     end, B].

%% ---------------- decode ----------------

-spec decode(binary()) -> {ok, term()} | error.
decode(Bin) ->
    try dec(Bin, 0) of
        {Term, <<>>} -> {ok, Term};
        _ -> error
    catch _:_ -> error
    end.

dec(_, D) when D > ?MAX_DEPTH -> error(too_deep);
dec(<<B, R/binary>>, D) -> dec(B, R, D).

dec(B, R, _) when B =< 16#7f -> {B, R};
dec(B, R, D) when B >= 16#80, B =< 16#8f -> dec_map(B band 15, R, D);
dec(B, R, D) when B >= 16#90, B =< 16#9f -> dec_arr(B band 15, R, D, []);
dec(B, R, _) when B >= 16#a0, B =< 16#bf -> dec_str(B band 31, R);
dec(16#c0, R, _) -> {null, R};
dec(16#c2, R, _) -> {false, R};
dec(16#c3, R, _) -> {true, R};
dec(16#c4, <<L, R/binary>>, _) -> dec_bin(L, R);
dec(16#c5, <<L:16, R/binary>>, _) -> dec_bin(L, R);
dec(16#c6, <<L:32, R/binary>>, _) -> dec_bin(L, R);
dec(16#ca, <<F:32/float, R/binary>>, _) -> {F, R};
dec(16#cb, <<F:64/float, R/binary>>, _) -> {F, R};
dec(16#cc, <<V, R/binary>>, _) -> {V, R};
dec(16#cd, <<V:16, R/binary>>, _) -> {V, R};
dec(16#ce, <<V:32, R/binary>>, _) -> {V, R};
dec(16#cf, <<V:64, R/binary>>, _) -> {V, R};
dec(16#d0, <<V:8/signed, R/binary>>, _) -> {V, R};
dec(16#d1, <<V:16/signed, R/binary>>, _) -> {V, R};
dec(16#d2, <<V:32/signed, R/binary>>, _) -> {V, R};
dec(16#d3, <<V:64/signed, R/binary>>, _) -> {V, R};
dec(16#d9, <<L, R/binary>>, _) -> dec_str(L, R);
dec(16#da, <<L:16, R/binary>>, _) -> dec_str(L, R);
dec(16#db, <<L:32, R/binary>>, _) -> dec_str(L, R);
dec(16#dc, <<N:16, R/binary>>, D) -> dec_arr(N, R, D, []);
dec(16#dd, <<N:32, R/binary>>, D) -> dec_arr(N, R, D, []);
dec(16#de, <<N:16, R/binary>>, D) -> dec_map(N, R, D);
dec(16#df, <<N:32, R/binary>>, D) -> dec_map(N, R, D);
dec(B, R, _) when B >= 16#e0 -> {B - 256, R}.

dec_str(L, R) ->
    <<S:L/binary, R2/binary>> = R,
    true = is_binary(unicode:characters_to_binary(S, utf8, utf8)),
    {S, R2}.

dec_bin(L, R) ->
    <<B:L/binary, R2/binary>> = R,
    {base64:encode(B), R2}.

dec_arr(0, R, _, Acc) -> {lists:reverse(Acc), R};
dec_arr(N, R, D, Acc) ->
    {V, R2} = dec(R, D + 1),
    dec_arr(N - 1, R2, D, [V | Acc]).

dec_map(N, R, D) -> dec_map(N, R, D, #{}).

dec_map(0, R, _, Acc) -> {Acc, R};
dec_map(N, R, D, Acc) ->
    {K, R1} = dec(R, D + 1),
    true = is_binary(K),
    {V, R2} = dec(R1, D + 1),
    dec_map(N - 1, R2, D, Acc#{K => V}).
