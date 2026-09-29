# Peer wire protocol

Once the tracker hands you peers, you talk to them in BEP 3 peer wire messages. This feature parses and builds the handshake, all nine message types, and the connection state machine as pure functions in `dev.cljtoc.protocol.peer` and `dev.cljtoc.protocol.peer-state`. Byte arrays are the boundary — TCP framing belongs to the coordination layer, never here.

## Handshake

Every connection opens with exactly 68 bytes:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | Protocol string length (`0x13` = 19) |
| 1 | 19 | Protocol string (`BitTorrent protocol`) |
| 20 | 8 | Reserved extension bytes (parsed and preserved, never acted on) |
| 28 | 20 | info-hash |
| 48 | 20 | peer-id |

Reject wrong protocol strings, short reads, and mismatched info-hashes with error maps. Accept any 20-byte peer-id.

## Messages

Every message is a 4-byte big-endian length prefix followed by an optional id byte and payload:

| ID | Type | Payload |
|---|---|---|
| — | keep-alive | None (4 zero bytes total); heartbeat only, changes no state |
| 0 | choke | None |
| 1 | unchoke | None |
| 2 | interested | None |
| 3 | not-interested | None |
| 4 | have | Piece index (4 bytes) |
| 5 | bitfield | Bit array of owned pieces |
| 6 | request | Piece index, byte offset, byte length (12 bytes) |
| 7 | piece | Piece index, byte offset, block data |
| 8 | cancel | Piece index, byte offset, byte length (12 bytes) |

Parsing a built message returns the original fields for every well-formed input — the round-trip property, checked by generative tests. Unknown ids and truncated payloads produce descriptive errors.

## Connection state

`PeerState` is immutable and tracks both directions of choking and interest plus the peer's advertised pieces:

* `am-choking`, `am-interested`, `peer-choking`, `peer-interested`
* bitfield with total piece count for validation

You advance it with one pure transition, `(state, message) → state`: `unchoke` clears `peer-choking`, `have` marks a piece available, `bitfield` replaces availability, keep-alive and data messages change nothing. Reduce over a message sequence to replay a connection in tests with zero setup.

## Rules the layer enforces

* Block requests and piece payloads must not exceed the 16 KiB (16,384-byte) standard block size.
* Indices are non-negative; info-hash and peer-id are exactly 20 bytes.
* Bitfields longer than the piece count ignore extra bits; shorter ones treat missing bits as unavailable.
* Extension protocols (BEP 6 fast peers, BEP 10 extensions) are out of scope.

## Key decisions

* **Clojure records** (`PeerHandshake`, `Have`, `Request`, …) with `:message-type` keyword dispatch — named fields document themselves and multimethods split parsing from building.
* **`BitSet` wrapped in an immutable abstraction** for peer bitfields — O(1) availability checks at 1 bit per piece. (Piece *tracking* uses persistent sets instead; see [piece management](piece-management.md).)
* **Errors as data** (`{:error :unknown-message-type :message ...}`) so callers compose handling with `if-let` instead of catching.
* **No TCP in this layer** — framing a stream into complete messages is coordination's job.

## Further reading

* [Peer wire API](peer-wire-api.md) — function-level reference with examples.
* [Peer wire contracts](peer-wire-contracts.md) — signatures, invariants, error keywords.
