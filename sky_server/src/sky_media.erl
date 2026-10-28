%% File upload / download for attachments (images, voice notes, files).
%% Both need "Authorization: Bearer <session token>". File ids are random 128-bit.
-module(sky_media).
-behaviour(cowboy_handler).
-export([init/2]).
-include_lib("kernel/include/file.hrl").

-define(MAX_BYTES, 26214400).   %% 25 MB

init(Req0, Op) ->
    case auth(Req0) of
        error -> {ok, cowboy_req:reply(401, #{}, <<"unauthorized">>, Req0), Op};
        {ok, _Phone} -> handle(Op, cowboy_req:method(Req0), Req0)
    end.

handle(upload, <<"POST">>, Req) -> upload(Req);
handle(download, <<"GET">>, Req) -> download(Req);
handle(Op, _, Req) -> {ok, cowboy_req:reply(405, #{}, <<>>, Req), Op}.

upload(Req0) ->
    Id = string:lowercase(binary:encode_hex(crypto:strong_rand_bytes(16))),
    Path = path(Id),
    ok = filelib:ensure_dir(Path),
    {ok, Fd} = file:open(Path, [write, binary, raw]),
    Result = read_body(Req0, Fd, 0),
    file:close(Fd),
    case Result of
        {ok, Req} ->
            Body = jsx:encode(#{<<"id">> => Id}),
            {ok, cowboy_req:reply(200, #{<<"content-type">> => <<"application/json">>},
                                  Body, Req), upload};
        {too_large, Req} ->
            file:delete(Path),
            {ok, cowboy_req:reply(413, #{}, <<"too large">>, Req), upload}
    end.

read_body(Req0, Fd, Size) ->
    {Status, Data, Req} = cowboy_req:read_body(Req0, #{length => 1000000, period => 20000}),
    Size1 = Size + byte_size(Data),
    case Size1 > ?MAX_BYTES of
        true -> {too_large, Req};
        false ->
            ok = file:write(Fd, Data),
            case Status of
                ok -> {ok, Req};
                more -> read_body(Req, Fd, Size1)
            end
    end.

download(Req) ->
    Id = cowboy_req:binding(id, Req),
    case valid_id(Id) of
        false -> {ok, cowboy_req:reply(404, #{}, <<>>, Req), download};
        true ->
            Path = path(Id),
            Headers = #{<<"content-type">> => <<"application/octet-stream">>},
            case file:read_file_info(Path) of
                {ok, #file_info{size = 0}} ->
                    {ok, cowboy_req:reply(200, Headers, <<>>, Req), download};
                {ok, #file_info{size = Size}} ->
                    {ok, cowboy_req:reply(200, Headers, {sendfile, 0, Size, Path}, Req), download};
                {error, _} ->
                    {ok, cowboy_req:reply(404, #{}, <<>>, Req), download}
            end
    end.

auth(Req) ->
    case cowboy_req:header(<<"authorization">>, Req) of
        <<"Bearer ", Token/binary>> -> sky_db:phone_for_token(Token);
        _ -> error
    end.

valid_id(Id) when is_binary(Id), byte_size(Id) =:= 32 ->
    lists:all(fun(C) -> (C >= $0 andalso C =< $9) orelse (C >= $a andalso C =< $f) end,
              binary_to_list(Id));
valid_id(_) -> false.

path(Id) ->
    filename:join(application:get_env(sky, media_dir, "media"), to_list(Id)).

to_list(B) when is_binary(B) -> binary_to_list(B);
to_list(L) -> L.
