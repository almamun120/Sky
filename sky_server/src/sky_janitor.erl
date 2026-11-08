%% Hourly cleanup: expired sessions, old undelivered items, old uploaded files.
%% Config: media_ttl_days, pending_ttl_days (days, default 30 each);
%%         media_max_total_mb (default off): when all uploaded files together are bigger, the oldest ones are deleted until they fit.
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
    Dir = application:get_env(sky, media_dir, "media"),
    purge_media(Dir, MediaDays),
    purge_owners(Dir, MediaDays),
    cap_total(Dir, application:get_env(sky, media_max_total_mb, undefined)).

%% Forget the owner rows of files that are gone or too old (and delete the old files), which gives their quota back.
purge_owners(Dir, Days) ->
    Cutoff = os:system_time(millisecond) - Days * 86400000,
    lists:foreach(
      fun({Id, _Phone, _Size, Ts}) ->
              P = filename:join(Dir, binary_to_list(Id)),
              case Ts < Cutoff orelse not filelib:is_regular(P) of
                  true -> file:delete(P), sky_db:del_upload(Id);
                  false -> ok
              end
      end, sky_db:all_uploads()).

%% Keep all uploads together under media_max_total_mb: delete the oldest first.
cap_total(_Dir, Mb) when not is_integer(Mb); Mb =< 0 -> ok;
cap_total(Dir, Mb) ->
    Limit = Mb * 1048576,
    All = sky_db:all_uploads(),                      %% oldest first
    Total = lists:sum([Sz || {_, _, Sz, _} <- All]),
    drop_oldest(Dir, All, Total, Limit).

drop_oldest(_Dir, _, Total, Limit) when Total =< Limit -> ok;
drop_oldest(_Dir, [], _, _) -> ok;
drop_oldest(Dir, [{Id, _, Sz, _} | Rest], Total, Limit) ->
    file:delete(filename:join(Dir, binary_to_list(Id))),
    sky_db:del_upload(Id),
    drop_oldest(Dir, Rest, Total - Sz, Limit).

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
