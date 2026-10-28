%% Hourly cleanup: expired sessions, old undelivered items, old uploaded files.
%% Config (days): media_ttl_days, pending_ttl_days  (default 30 each)
-module(sky_janitor).
-behaviour(gen_server).
-export([start_link/0]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2]).
-include_lib("kernel/include/file.hrl").

-define(EVERY_MS, 3600000).

start_link() -> gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

init([]) ->
    erlang:send_after(60000, self(), run),
    {ok, #{}}.

handle_call(_, _From, S) -> {reply, ok, S}.
handle_cast(_, S) -> {noreply, S}.

handle_info(run, S) ->
    catch run(),
    erlang:send_after(?EVERY_MS, self(), run),
    {noreply, S};
handle_info(_, S) -> {noreply, S}.

run() ->
    PendingDays = application:get_env(sky, pending_ttl_days, 30),
    MediaDays = application:get_env(sky, media_ttl_days, 30),
    sky_db:purge_sessions(),
    sky_db:purge_pending(os:system_time(millisecond) - PendingDays * 86400000),
    purge_media(application:get_env(sky, media_dir, "media"), MediaDays).

purge_media(Dir, Days) ->
    Cutoff = os:system_time(second) - Days * 86400,
    case file:list_dir(Dir) of
        {ok, Files} ->
            lists:foreach(
              fun(F) ->
                      P = filename:join(Dir, F),
                      case file:read_file_info(P, [{time, posix}]) of
                          {ok, #file_info{type = regular, mtime = M}} when M < Cutoff ->
                              file:delete(P);
                          _ -> ok
                      end
              end, Files);
        _ -> ok
    end.
