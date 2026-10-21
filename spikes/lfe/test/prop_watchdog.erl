%% PropEr properties for the LFE watchdog's stall decision.
%%
%% The oracles are laws, not a rerun of the function under test: a marker
%% that moved is never a stall whatever the clock says, elapsed time below
%% the deadline is never a stall, a stall holds while the marker stands
%% still, and the deadline is reached at exactly the deadline. The marker
%% count is checked against the declared inputs.
%%
%% PropEr generators are size-parameterised: choose(1, 100000) draws from
%% the current size, which starts at 1. Deadlines are resized explicitly
%% where a law needs a value above the deadline.
-module(prop_watchdog).

-include_lib("proper/include/proper.hrl").

-export([prop_moving_marker_is_never_a_stall/0,
         prop_time_below_the_deadline_is_never_a_stall/0,
         prop_the_deadline_is_reached_exactly/0,
         prop_a_stall_holds_while_the_marker_stands_still/0,
         prop_marker_counts_blocks_and_peers/0]).

marker() ->
    ?LET({Blocks, Peers}, {integer(), integer()}, [Blocks, Peers]).

deadline() ->
    ?LET(Deadline, choose(1, 100000), Deadline).

prop_moving_marker_is_never_a_stall() ->
    ?FORALL({Marker, Previous, LastProgress, Now, Deadline},
            {marker(), marker(), nat(), nat(), deadline()},
            Marker =:= Previous orelse
                not watchdog:'stalled?'(Marker, Previous, LastProgress, Now, Deadline)).

prop_time_below_the_deadline_is_never_a_stall() ->
    ?FORALL({Marker, LastProgress, Gap, Deadline},
            {marker(), nat(), nat(), deadline()},
            Gap >= Deadline orelse
                not watchdog:'stalled?'(Marker, Marker, LastProgress,
                                        LastProgress + Gap, Deadline)).

prop_the_deadline_is_reached_exactly() ->
    ?FORALL({Marker, LastProgress, Deadline},
            {marker(), nat(), deadline()},
            watchdog:'stalled?'(Marker, Marker, LastProgress,
                                LastProgress + Deadline, Deadline)).

prop_a_stall_holds_while_the_marker_stands_still() ->
    ?FORALL({Marker, LastProgress, Offset, Later, Deadline},
            {marker(), nat(), nat(), nat(), deadline()},
            Offset < Deadline orelse
                begin
                    Now = LastProgress + Offset,
                    watchdog:'stalled?'(Marker, Marker, LastProgress, Now, Deadline)
                        =:= watchdog:'stalled?'(Marker, Marker, LastProgress,
                                                Now + Later, Deadline)
                end).

prop_marker_counts_blocks_and_peers() ->
    ?FORALL({PeerBlocks, Verified},
            {list({choose(1, 100), choose(0, 5)}), list(choose(0, 20))},
            begin
                Pairs = lists:ukeysort(1, PeerBlocks),
                Blocks = maps:from_list([{Peer, lists:duplicate(Count, block)}
                                         || {Peer, Count} <- Pairs]),
                Record = #{'blocks-received' => Blocks,
                           'verified' => Verified,
                           'active-peers' => [Peer || {Peer, _} <- Pairs]},
                [BlocksTotal, PeersSeen] = watchdog:'progress-marker'(Record),
                BlocksTotal =:= lists:sum([Count || {_, Count} <- Pairs])
                    + length(Verified)
                    andalso PeersSeen =:= length(Pairs)
            end).
