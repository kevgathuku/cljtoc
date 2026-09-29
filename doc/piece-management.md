# Piece management

This is the pure domain heart of the download engine, in `dev.cljtoc.domain.pieces`. You track every piece's status, pick the next piece to fetch, split pieces into wire-sized blocks, verify received bytes, and detect endgame — all with deterministic functions and no network, disk, or clock.

## Piece state

`PieceState` snapshots the whole torrent: total piece count plus three disjoint sets — `needed`, `in-flight`, `verified`. Transitions return new states and never mutate:

* `needed → in-flight` when you send requests,
* `in-flight → verified` when the hash checks out,
* `in-flight → needed` when verification fails and you requeue.

Query counts per status any time; when nothing remains `needed` or `in-flight`, the torrent is complete.

## Rarest-first selection

`select-piece` intersects what the peer has with what you still need, then picks the piece fewest connected peers hold, breaking ties by lowest index. It returns `{:ok piece-index}` or `{:ok nil}` when the peer holds nothing you need. In-flight pieces stay unselected — until endgame.

## Blocks

The wire speaks blocks, not pieces. `piece-blocks` takes a piece index, the standard piece length, and the total torrent length, and returns the gapless, non-overlapping block list covering exactly that piece. Every block is at most 16 KiB (16,384 bytes); only the torrent's final piece may be shorter. Call it with three integers — it never touches the torrent map.

## Verification

`verify-piece` compares assembled piece bytes against the 20-byte SHA-1 from the torrent metadata (reusing `bencode/sha1-hash`) and returns a typed pass or fail carrying the piece index. Wrong length, empty input, or a single flipped byte all fail. Pass means safe to write; fail means discard and requeue.

## Endgame

When `needed + in-flight` drops to your threshold (default 20, caller-supplied), `endgame?` returns true and you switch to `select-pieces-endgame`: request the leftovers from every peer holding them at once. The orchestrator cancels the redundant requests as pieces complete, killing the long-tail stall.

## Key decisions

* **Persistent sets** for `PieceState`, not `BitSet` — O(log N) transitions with plain-data equality and serialization, and no clone-before-mutate dance in the domain layer.
* **Peer availability as Clojure sets**, not BitSets — the coordination layer converts once at the call site, keeping JVM types out of the domain.
* **Linear rarest-first count** (candidates × peers) — microseconds at typical swarm sizes; no frequency table to maintain.
* **Fixed 16,384-byte blocks** per BitTorrent convention, not configurable per torrent.
* **Errors as typed maps** — `:invalid-transition`, `:invalid-input`, `:hash-mismatch` — so callers branch on keywords, never exceptions.

## Further reading

* [Piece contracts](piece-contracts.md) — signatures, state-transition table, invariants, usage example.
