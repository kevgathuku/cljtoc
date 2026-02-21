# Research: Piece Management

**Feature**: 005-piece-management | **Phase**: 0 — Research
**Date**: 2026-02-21

---

## Decision 1: SHA-1 Reuse

**Decision**: Reuse `dev.cljtoc.domain.bencode/sha1-hash` for piece verification.

**Rationale**: The function is already implemented, correctly returns exactly 20 bytes, is a pure `^bytes → ^bytes` transformation, and lives in the same domain layer. No new dependency is introduced.

**Alternatives considered**:
- Re-implement directly with `java.security.MessageDigest` — rejected; duplicates existing code with no benefit
- Move to a shared `util` namespace — rejected; over-engineering; `bencode/sha1-hash` is already accessible from the domain layer without circular dependency

---

## Decision 2: PieceState Representation

**Decision**: Use `defrecord PieceState [total-pieces needed in-flight verified]` with Clojure **persistent sets** (`#{...}`) for `needed`, `in-flight`, and `verified` fields.

**Rationale**:
- Persistent sets give O(log N) membership tests and O(log N) transitions via `conj`/`disj` with no mutation concern — no clone-before-mutate overhead like BitSets require
- Fully serializable (plain Clojure data) — no Java interop needed in the domain layer
- Clean `(= state other-state)` equality for testing (BitSet equality requires `.equals` interop)
- Consistent with project principle: "domain data structures MUST be immutable and serializable"
- For torrents with 100k pieces, persistent sets remain fast enough given state transitions are infrequent relative to network events

**Alternatives considered**:
- `java.util.BitSet` (like `peer_state.clj`) — rejected; requires clone-before-mutate pattern and Java interop in domain layer; persistent sets are simpler and fully immutable
- Vector of status keywords — rejected; O(N) lookup for piece membership checks

---

## Decision 3: Peer Availability Input Format

**Decision**: `select-piece` accepts peer availability as **Clojure persistent sets of piece indices** (`#{0 3 7 ...}`), not Java BitSets.

**Rationale**:
- Keeps domain layer free of Java types (BitSet lives in the protocol/coordination layer)
- The coordination layer (feature 006) converts `peer_state` BitSets to sets when calling `select-piece` — this is a one-line conversion: `(set (.stream bitset) ...)`
- Pure Clojure data enables trivial unit testing with literal set values

**Alternatives considered**:
- Accept `java.util.BitSet` directly — rejected; violates domain purity (JVM type), complicates testing, creates implicit coupling to protocol layer representation

---

## Decision 4: Rarest-First Algorithm

**Decision**: Linear frequency count over candidate pieces.

**Algorithm**:
1. Candidate pieces = `(set/intersection peer-available (:needed state))`
2. For each candidate, count how many peer-available-sets in `all-peers-available` contain it
3. Select the candidate with the minimum count; break ties by lowest piece index
4. Return `{:ok piece-index}` or `{:ok nil}` (no selectable piece)

**Complexity**: O(C × P) where C = candidate count, P = connected peer count. For typical swarms (C ≤ 1000 candidates, P ≤ 50 peers) this executes in microseconds — well within SC-001's 1ms budget.

**Alternatives considered**:
- Maintain a sorted frequency table as part of PieceState — rejected; premature optimisation; adds state management complexity without measurable benefit at typical scales
- Random selection among equally-rare pieces — rejected; lowest piece index is deterministic and testable

---

## Decision 5: Block Decomposition Inputs

**Decision**: `piece-blocks` accepts three integers: `piece-index`, `standard-piece-length`, `total-length`. Returns a vector of `Block` records.

**Rationale**:
- Decouples from the torrent map structure; callers extract the two integers they need
- The function is entirely arithmetic; no other torrent fields are needed
- Matches the spec's description of US3 as a pure mathematical decomposition

**Block size**: Fixed constant 16,384 bytes (16 KiB) per BitTorrent convention. Not configurable.

**Last-piece logic**:
```
actual-piece-length = if last-piece?
                        (mod total-length standard-piece-length)
                        standard-piece-length
                      ;; if mod = 0, piece is full size
```
If `(mod total-length standard-piece-length) = 0`, the last piece is also `standard-piece-length`.

---

## Decision 6: Endgame Mode

**Decision**: `endgame?` is a pure predicate: `(+ (count needed) (count in-flight)) <= threshold`.

In endgame mode, `select-piece` is replaced by `select-pieces-endgame` which returns all pieces that the peer has in either `needed` OR `in-flight` state (for duplicate requesting).

**Threshold default**: 20 remaining pieces (from spec assumption). Caller supplies the value; the domain function does not hard-code it.

---

## Decision 7: Error Handling

**Decision**: Same `{:ok value} / {:error :keyword :message string}` pattern used throughout the codebase.

State transition errors (e.g., marking a piece verified that isn't in-flight) return `{:error :invalid-transition :message "..."}`. Out-of-range inputs return `{:error :invalid-input :message "..."}`. Verification failures return `{:error :hash-mismatch :piece-index n :message "..."}`.

---

## Decision 8: clojure.spec Coverage

**Decision**: Add `s/def` specs for all domain types and `s/fdef` for all public functions with `:fn` invariants, following the same pattern as `peer.clj`, `peer_state.clj`, `bencode.clj`, and `torrent.clj`.

Key invariants to encode:
- State transitions preserve `total-pieces`
- `(+ needed-count in-flight-count verified-count) = total-pieces` always
- `piece-blocks` output covers exactly `piece-length` bytes total
- `verify-piece` pass result carries the same `piece-index` as input

---

## Technology Summary

| Concern | Decision |
|---------|----------|
| SHA-1 | Reuse `bencode/sha1-hash` |
| PieceState | `defrecord` with persistent sets |
| Peer availability input | Clojure persistent sets |
| Rarest-first | Linear frequency count, lowest-index tie-break |
| Block decomposition | Three-integer inputs, fixed 16384 block size |
| Endgame | Caller-supplied threshold, separate `select-pieces-endgame` function |
| Errors | `{:ok} / {:error :keyword :message}` |
| Specs | `s/def` + `s/fdef` with `:fn` invariants |
