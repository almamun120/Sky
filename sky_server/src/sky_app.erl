-module(sky_app).
-behaviour(application).
-export([start/2, stop/1]).

start(_Type, _Args) ->
    ok = sky_db:init(),
    {ok, Sup} = sky_sup:start_link(),
    Dispatch = cowboy_router:compile([{'_', [
        {"/ws", sky_ws, []},
        {"/push", sky_push_ws, []},
        {"/upload", sky_media, upload},
        {"/media/:id", sky_media, download}]}]),
    Port = application:get_env(sky, port, 8080),
    {ok, _} = cowboy:start_clear(sky_http, [{port, Port}],
                                 #{env => #{dispatch => Dispatch}}),
    {ok, Sup}.

stop(_State) ->
    cowboy:stop_listener(sky_http),
    ok.
