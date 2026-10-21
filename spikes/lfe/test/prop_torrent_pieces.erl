%% PropEr properties for the LFE piece module.
%%
%% Oracles are computed from the declared inputs (lengths, peer sets,
%% index ranges), never by calling the function under test again.
%%
%% PropEr generators are size-parameterised: choose(1, 1048576) draws from
%% the current size, which starts at 1. Large domains are resized explicitly
%% or the properties only ever see tiny values.
-module(prop_torrent_pieces).

-include_lib("proper/include/proper.hrl").

-export([prop_initial_state/0,
         prop_partition_invariant/0,
         prop_complete_matches_verified_count/0,
         prop_endgame_matches_remaining_count/0,
         prop_select_piece_is_safe/0,
         prop_select_piece_is_rarest_first/0,
         prop_endgame_selection/0,
         prop_piece_lengths_sum_to_total/0,
         prop_blocks_cover_the_piece/0,
         prop_verify_accepts_matching_hash/0,
         prop_verify_rejects_bad_hash_length/0,
         prop_invalid_transitions_are_rejected/0]).

piece_state() ->
    ?LET({Total, Buckets},
         {choose(1, 12), vector(12, choose(0, 2))},
         begin
             Assigned = lists:zip(lists:seq(0, Total - 1),
                                  lists:sublist(Buckets, Total)),
             Bucket = fun(N) ->
                              sets:from_list([I || {I, B} <- Assigned, B =:= N])
                      end,
             #{'total-pieces' => Total,
               'needed' => Bucket(0),
               'in-flight' => Bucket(1),
               'verified' => Bucket(2)}
         end).

index_set() ->
    ?LET(Indices, list(choose(0, 30)), sets:from_list(Indices)).

peer_lists() ->
    ?LET(Peers, list(index_set()), Peers).

prop_initial_state() ->
    ?FORALL(Total, choose(1, 50),
            begin
                State = torrent_pieces:'initial-piece-state'(Total),
                maps:get('total-pieces', State) =:= Total
                    andalso maps:get('needed', State)
                            =:= sets:from_list(lists:seq(0, Total - 1))
                    andalso sets:size(maps:get('in-flight', State)) =:= 0
                    andalso sets:size(maps:get('verified', State)) =:= 0
            end).

prop_partition_invariant() ->
    ?FORALL({State, Index},
            {piece_state(), choose(0, 30)},
            begin
                Total = maps:get('total-pieces', State),
                Piece = Index rem Total,
                Step = fun(S, Result) ->
                               case Result of
                                   {ok, Next} -> Next;
                                   #{'error' := _} -> S
                               end
                       end,
                S1 = Step(State, torrent_pieces:'mark-in-flight'(State, Piece)),
                S2 = Step(S1, torrent_pieces:'mark-verified'(S1, Piece)),
                S3 = Step(S2, torrent_pieces:'requeue-verified'(S2, Piece)),
                S4 = Step(S1, torrent_pieces:'requeue-piece'(S1, Piece)),
                lists:all(fun(S) -> partition_holds(S, Total) end,
                          [State, S1, S2, S3, S4])
            end).

partition_holds(State, Total) ->
    Needed = maps:get('needed', State),
    InFlight = maps:get('in-flight', State),
    Verified = maps:get('verified', State),
    All = sets:to_list(sets:union(Needed, sets:union(InFlight, Verified))),
    sets:size(Needed) + sets:size(InFlight) + sets:size(Verified) =:= Total
        andalso lists:all(fun(I) -> I >= 0 andalso I < Total end, All).

prop_complete_matches_verified_count() ->
    ?FORALL(State, piece_state(),
            torrent_pieces:'complete?'(State)
                =:= (sets:size(maps:get('verified', State))
                     =:= maps:get('total-pieces', State))).

prop_endgame_matches_remaining_count() ->
    ?FORALL({State, Threshold},
            {piece_state(), choose(0, 20)},
            begin
                Remaining = sets:size(maps:get('needed', State))
                    + sets:size(maps:get('in-flight', State)),
                torrent_pieces:'endgame?'(State, Threshold)
                    =:= (Remaining =< Threshold)
            end).

prop_select_piece_is_safe() ->
    ?FORALL({State, Peers},
            {piece_state(), peer_lists()},
            begin
                Available = sets:from_list(lists:append([sets:to_list(P) || P <- Peers])),
                Needed = maps:get('needed', State),
                Result = torrent_pieces:'select-piece'(State, Available, Peers),
                case Result of
                    {ok, nil} ->
                        sets:size(sets:intersection(Available, Needed)) =:= 0;
                    {ok, Piece} ->
                        sets:is_element(Piece, Needed)
                            andalso sets:is_element(Piece, Available);
                    _ ->
                        false
                end
            end).

prop_select_piece_is_rarest_first() ->
    ?FORALL({State, Peers},
            {piece_state(), peer_lists()},
            begin
                Needed = maps:get('needed', State),
                Available = sets:from_list(lists:append([sets:to_list(P) || P <- Peers])),
                Candidates = lists:sort(sets:to_list(sets:intersection(Available, Needed))),
                Frequency = fun(I) ->
                                    length([P || P <- Peers, sets:is_element(I, P)])
                            end,
                Result = torrent_pieces:'select-piece'(State, Available, Peers),
                case Candidates of
                    [] ->
                        Result =:= {ok, nil};
                    _ ->
                        Best = lists:min([{Frequency(I), I} || I <- Candidates]),
                        case Result of
                            {ok, Piece} -> {Frequency(Piece), Piece} =:= Best;
                            _ -> false
                        end
                end
            end).

prop_endgame_selection() ->
    ?FORALL({State, Peers},
            {piece_state(), peer_lists()},
            begin
                Available = sets:from_list(lists:append([sets:to_list(P) || P <- Peers])),
                Remaining = sets:union(maps:get('needed', State),
                                       maps:get('in-flight', State)),
                Expected = lists:sort(sets:to_list(
                                          sets:intersection(Available, Remaining))),
                torrent_pieces:'select-pieces-endgame'(State, Available)
                    =:= {ok, Expected}
            end).

boundary_length() ->
    ?LET({Multiple, Delta},
         {choose(1, 64), oneof([0, 1, -1])},
         max(1, Multiple * 16384 + Delta)).

prop_piece_lengths_sum_to_total() ->
    ?FORALL({TotalPieces, Standard, Remainder},
            {choose(1, 25),
             resize(65536, choose(1, 65536)),
             resize(65536, choose(1, 65536))},
            begin
                ActualRemainder = min(Remainder, Standard),
                TotalLength = (TotalPieces - 1) * Standard + ActualRemainder,
                Lengths = [torrent_pieces:'piece-length'(I, Standard, TotalLength)
                           || I <- lists:seq(0, TotalPieces - 1)],
                lists:sum(Lengths) =:= TotalLength
                    andalso lists:all(fun(L) -> L >= 1 andalso L =< Standard end, Lengths)
                    andalso torrent_pieces:'piece-length'(TotalPieces, Standard, TotalLength) =:= 0
            end).

prop_blocks_cover_the_piece() ->
    ?FORALL({TotalPieces, Standard, Remainder, Index},
            {choose(1, 20),
             oneof([boundary_length(),
                    resize(1048576, choose(1, 1048576))]),
             oneof([boundary_length(),
                    resize(1048576, choose(1, 1048576))]),
             choose(0, 19)},
            begin
                ActualRemainder = min(Remainder, Standard),
                TotalLength = (TotalPieces - 1) * Standard + ActualRemainder,
                PieceIndex = Index rem TotalPieces,
                PieceStart = PieceIndex * Standard,
                PieceEnd = min((PieceIndex + 1) * Standard, TotalLength),
                Expected = PieceEnd - PieceStart,
                case torrent_pieces:'piece-blocks'(PieceIndex, Standard, TotalLength) of
                    {ok, Blocks} ->
                        Lengths = [maps:get('length', B) || B <- Blocks],
                        Offsets = [maps:get('offset', B) || B <- Blocks],
                        ExpectedOffsets = running_offsets(Lengths),
                        lists:sum(Lengths) =:= Expected
                            andalso lists:all(fun(L) -> L >= 1 andalso L =< 16384 end, Lengths)
                            andalso Offsets =:= ExpectedOffsets
                            andalso length(Blocks) =:= (Expected + 16383) div 16384;
                    _ ->
                        false
                end
            end).

prop_verify_accepts_matching_hash() ->
    ?FORALL({Data, PieceIndex},
            {non_empty(binary()), choose(0, 999)},
            begin
                code:add_patha(elixir_ebin()),
                Hash = crypto:hash(sha, Data),
                torrent_pieces:'verify-piece'(PieceIndex, Data, Hash)
                    =:= {ok, PieceIndex}
            end).

prop_verify_rejects_bad_hash_length() ->
    ?FORALL({Data, Length},
            {non_empty(binary()), oneof([choose(0, 19), choose(21, 40)])},
            begin
                code:add_patha(elixir_ebin()),
                Hash = case Length < 20 of
                           true -> binary:part(crypto:hash(sha, Data), 0, Length);
                           false -> crypto:strong_rand_bytes(Length)
                       end,
                case torrent_pieces:'verify-piece'(0, Data, Hash) of
                    #{'error' := 'invalid-input'} -> true;
                    _ -> false
                end
            end).

prop_invalid_transitions_are_rejected() ->
    ?FORALL({State, Index},
            {piece_state(), choose(0, 30)},
            begin
                Total = maps:get('total-pieces', State),
                Piece = Index rem Total,
                Needed = maps:get('needed', State),
                InFlight = maps:get('in-flight', State),
                Verified = maps:get('verified', State),
                Check = fun(Result, InSource) ->
                                case InSource of
                                    true -> element(1, Result) =:= ok;
                                    false -> maps:get('error', Result) =:= 'invalid-transition'
                                end
                        end,
                Check(torrent_pieces:'mark-in-flight'(State, Piece),
                      sets:is_element(Piece, Needed))
                    andalso Check(torrent_pieces:'mark-verified'(State, Piece),
                                  sets:is_element(Piece, InFlight))
                    andalso Check(torrent_pieces:'requeue-piece'(State, Piece),
                                  sets:is_element(Piece, InFlight))
                    andalso Check(torrent_pieces:'requeue-verified'(State, Piece),
                                  sets:is_element(Piece, Verified))
            end).

running_offsets(Lengths) ->
    {Reversed, _} = lists:foldl(fun(L, {Acc, Sum}) -> {[Sum | Acc], Sum + L} end,
                                {[], 0},
                                Lengths),
    lists:reverse(Reversed).

elixir_ebin() ->
    case os:getenv("ELIXIR_EBIN") of
        false -> "/Users/kevin/code/elixir/torrent_client/_build/dev/lib/torrent_client/ebin";
        Path -> Path
    end.
