# Architecture

This client downloads and seeds files from the BitTorrent network while keeping every layer cleanly separated. You can reason about each part in isolation: pure functions decide, ports perform effects, supervisors recover.

The target process model is a Transmission-style daemon: one long-lived session process owns every torrent across CLI invocations, and each CLI call is a thin client against it. The current blocking CLI (one session value per invocation) is the stepping stone, not the end state. See [ADR-0012](../docs/adr/0012-daemon-session.md).

## Layer map

The codebase follows four layers with strict one-way dependencies (lower layers never depend on higher ones):

```
Supervisor Layer   → lifecycle management, restart policies
         ↓
Coordination Layer → core.async channels, message routing, backpressure
         ↓
Protocol Layer     → bencode parsing, peer wire protocol, tracker protocol
         ↓
Domain Layer       → pure torrent logic (piece selection, state transitions, verification)
```

| Layer | Namespaces | Rule |
|---|---|---|
| Domain | `dev.cljtoc.domain.*` | Pure functions only. No I/O, no channels, no clock. State transitions look like `(state, event) → state`. |
| Protocol | `dev.cljtoc.protocol.*` | Pure parsing and encoding. No sockets, no channels. Byte arrays are the boundary. |
| Coordination | `dev.cljtoc.coordination.*` | core.async flows connecting domain decisions to effects. No business rules. |
| Orchestration | `dev.cljtoc.orchestration.*` | Download lifecycle: start, pause, resume, stop, progress. |
| Ports | `dev.cljtoc.ports.*` | Effect protocols plus real implementations (network, disk, time). |
| Supervision | `dev.cljtoc.supervision.session-supervisor`, `dev.cljtoc.supervision.download-supervisor`, `dev.cljtoc.supervision.peer-supervisor`, `dev.cljtoc.supervision.restart` | Workers crash on envelope-bounded errors and clean-exit on port `:error` returns; supervisors own restart decisions and budgets. The peer supervisor is one-for-one over the peer workers and owns the event channel they write to; the download supervisor is rest-for-one per torrent; the session supervisor owns one download-supervisor subtree per torrent so one flaky torrent never restarts its siblings; `restart` holds the sliding-window budget both levels share. See [ADR-0011](../docs/adr/0011-supervision-layer.md) as reversed in part by [ADR-0012](../docs/adr/0012-daemon-session.md). |

## Effect ports

Every side effect sits behind an injectable protocol, so you can test the whole system without I/O:

* `INetworkPort` — peer connections, message send/receive.
* `IDiskPort` — piece read/write, state persistence.
* `ITimePort` — timestamps, timeouts, intervals.
* `IRandomnessPort` — peer-selection jitter, protocol ids, backoff jitter.

In tests you inject in-memory doubles; in production you inject the real implementations.

## Target: daemon session

Build toward one long-lived session process, not a richer single CLI run:

* **One daemon owns the config dir.** It loads every torrent plus resume state at start (Transmission `tr_sessionLoadTorrents`), accepts torrent add/remove while running (`torrent-add`), persists per-torrent state periodically and on SIGTERM, and closes cleanly (Transmission `tr_sessionClose`).
* **CLI calls are thin clients.** `torrent.start` returns while the download runs; `status`, `pause`, and `resume` query the live supervisor instead of blocking until completion. Do not entrench the blocking `run-downloads` shape — `torrent-client-clj-0c9.14` replaces it.
* **One subtree per torrent.** Session supervisor → one download-supervisor subtree each (coordinator, peer supervisor, re-announcer, watchdog). Rest-for-one stays scoped per torrent (`torrent-client-clj-0c9.13`).
* **One shared instance per daemon.** A single peer port bind, peer id, bandwidth object, and queue scheduler serve all torrents. Two concurrent CLI runs must never double-bind them.

## Design principles

Six rules constrain every change. The authoritative wording lives in `AGENTS.md`; this is the short map:

1. **Pure domain core** — business logic is deterministic functions with no side effects.
2. **Explicit effect isolation** — effects happen only behind ports.
3. **Crash-only design** — restart is the recovery path; state rebuilds from durable storage.
4. **Supervision hierarchies** — every concurrent process has an explicit supervisor with a defined restart policy (one-for-one at the peer layer, rest-for-one per torrent at the download layer, session supervisor above them) and a restart budget that bounds churn. See [ADR-0011](../docs/adr/0011-supervision-layer.md) as reversed in part by [ADR-0012](../docs/adr/0012-daemon-session.md).
5. **Zero global state** — state travels in function arguments or lives in supervised components.
6. **Contract-first protocols** — interfaces are protocols first, so production and test implementations stay interchangeable.

## Core entities

* **Torrent** — one download job: info-hash, metadata, piece hashes, tracker URLs, runtime progress.
* **Piece** — fixed-size chunk with index, hash, length, and status (`needed`, `in-flight`, `verified`).
* **Block** — 16 KiB network transfer unit inside a piece (index, offset, length).
* **Peer** — remote client: peer-id, address, bitfield, choke/interest flags, transfer statistics.
* **Tracker** — announce endpoint (`:http` or `:udp`) with interval and last peer list.
* **Supervisor** — owns worker lifecycles with a restart strategy (one-for-one at the peer layer, rest-for-one per torrent at the download layer, session supervisor above them) and a restart budget that bounds churn in a sliding window.
* **Session** — the daemon's ownership of N downloads: one download-supervisor subtree per torrent under a session supervisor, sharing one peer port, peer id, bandwidth, and queue. The current `orchestration.session` record (one map per blocking CLI call) is the stepping stone, not the end state.
* **Daemon** — the long-lived session process: owns the config dir, loads all torrents plus resume state at start, accepts add/remove while running, persists periodically and on SIGTERM. Transmission equivalents: `tr_session` plus `transmission-daemon`.

## Feature docs

| Doc | Covers |
|---|---|
| [Bencode parser](bencode-parser.md) | `.torrent` parsing, info hash, validation ([contracts](bencode-contracts.md)) |
| [Tracker protocol](tracker-protocol.md) | HTTP/UDP announces, peer discovery ([HTTP](tracker-http.md), [UDP](tracker-udp.md), [fdef guide](tracker-fdef.md)) |
| [Peer wire protocol](peer-wire-protocol.md) | Handshake, 9 message types, connection state ([API](peer-wire-api.md), [contracts](peer-wire-contracts.md)) |
| [Piece management](piece-management.md) | Piece state, rarest-first, blocks, verification, endgame ([contracts](piece-contracts.md)) |
| [Download orchestration](download-orchestration.md) | Lifecycle, ports, supervision, persistence ([contracts](orchestration-contracts.md)) |

## Status and roadmap

Done: bencode parsing, tracker protocol, peer wire protocol, piece management, download orchestration, CLI (`parse`, `download`, `pause`, `resume`, `status`, `stop`), multi-torrent session value (`0c9.6`), session supervisor over one download-supervisor subtree per torrent (`0c9.13`).

Goal: Transmission-style daemon session — one long-lived process owns N torrents across CLI invocations (see [ADR-0012](../docs/adr/0012-daemon-session.md)). The current session record is the stepping stone, not the end state. Slices: RPC surface (`0c9.14`), periodic persistence plus SIGTERM shutdown (`0c9.18`), daemon shape (`0c9.12`).

Next: RPC surface, persistence, daemon — then seeding (`torrent.seed`), DHT, monitoring.

## Cross-cutting edge cases

Every feature defends against the same hostile conditions: malformed input, malicious peers, disk exhaustion, network loss, SIGKILL mid-write, and very large torrents. When you add a feature, cover each one or record why it cannot apply.
