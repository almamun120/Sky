%% Counters for monitoring (shown by /metrics, see sky_metrics.erl). Cheap: one atomic add, no process in between.
-module(sky_stats).
-export([init/0, inc/1, inc/2, get/1, uptime/0]).

init() ->
    persistent_term:put({?MODULE, ref}, counters:new(12, [write_concurrency])),
    persistent_term:put({?MODULE, start}, os:system_time(second)),
    ok.

inc(Name) -> inc(Name, 1).

inc(Name, By) ->
    case persistent_term:get({?MODULE, ref}, undefined) of
        undefined -> ok;
        Ref -> counters:add(Ref, idx(Name), By)
    end.

get(Name) ->
    case persistent_term:get({?MODULE, ref}, undefined) of
        undefined -> 0;
        Ref -> counters:get(Ref, idx(Name))
    end.

uptime() -> os:system_time(second) - persistent_term:get({?MODULE, start}, os:system_time(second)).

idx(conns_open) -> 1;       %% gauge: open /ws connections
idx(conns_total) -> 2;
idx(logins) -> 3;
idx(otp_requests) -> 4;
idx(msgs) -> 5;             %% one-to-one messages accepted
idx(group_msgs) -> 6;
idx(calls) -> 7;
idx(rate_limited) -> 8;
idx(errors) -> 9;           %% error frames sent
idx(sms_ok) -> 10;
idx(sms_fail) -> 11.
