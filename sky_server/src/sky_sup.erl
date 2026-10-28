-module(sky_sup).
-behaviour(supervisor).
-export([start_link/0, init/1]).

start_link() -> supervisor:start_link({local, ?MODULE}, ?MODULE, []).

init([]) ->
    Child = fun(M) -> #{id => M, start => {M, start_link, []},
                        restart => permanent, type => worker} end,
    {ok, {#{strategy => one_for_one, intensity => 5, period => 10},
          [Child(sky_router), Child(sky_otp), Child(sky_push),
           Child(sky_janitor), Child(sky_calls)]}}.
