# Torrent Downloading

The BitTorrent download domain: describing content, finding peers, exchanging blocks, and assembling verified files.

## Torrent description

**Torrent metainfo**:
The parsed description of a torrent's content and origin: tracker URLs, authorship fields, and the info dictionary.
_Avoid_: torrent metadata

**Info dictionary**:
The content-describing part of the metainfo: piece size, piece hashes, and single- or multi-file layout.

**Info hash**:
A torrent's canonical identity: the SHA-1 of the info dictionary's exact original bytes, never a re-encoding.

**Piece hash**:
The expected SHA-1 of one complete piece's bytes; the ground truth for integrity.
_Avoid_: info hash (torrent identity, not piece identity)

**Contained file**:
One file inside a multi-file torrent, named by path components with a byte length. Not the `.torrent` file itself.

## Swarm and tracking

**Tracker**:
The coordination server that records which peers participate in a torrent's swarm.

**Announce**:
A peer's periodic declaration to the tracker of identity, torrent, port, and transfer totals, plus its lifecycle event.
_Avoid_: request (means a block request on the wire)

**Peer**:
A participant in a torrent's swarm. Its location is a peer address; a live exchange with it is a peer connection.
_Avoid_: peer endpoint

**Peer connection**:
A live link to a peer carrying the handshake, availability, choking and interest posture, and block traffic.

**Peer address**:
A peer's network location: a host plus a port, written canonically as `host:port` (`[host]:port` for IPv6).
_Avoid_: peer string, endpoint, peer ID

**Swarm exhaustion**:
No live peer connections, no dials still in flight, and pieces still incomplete — the counter-based condition under which the coordinator fails a download for lack of peers. A closed event channel fails the download the same way; in a run the peer supervisor closes it when it is stopped, and it stays open after every worker exits because the re-announcer may dial more peers. Connected-but-choking peers and tracker re-announce are deliberately outside this definition. Supervisor exhaustion (per-supervisor restart intensity exceeded) is also a download-failure path.

## Lifecycle and supervision

**Worker**:
A concurrent unit whose lifecycle is owned by a supervisor. Workers exit cleanly on envelope-bounded errors (an `INetworkPort` returning `{:error …}`) and crash on anything else (spec failures, NPEs, malformed-message exceptions). The supervisor observes both through the worker's done-channel close.
_Avoid_: thread, goroutine, task ("daemon" alone always means the daemon process below, never a worker)

**Supervisor**:
The component responsible for a worker's lifecycle: detecting exits or crashes, deciding restart or drop, and enforcing the restart budget that bounds in-flight churn. Sits above the coordination layer in the four-layer architecture. The build distinguishes a peer supervisor (one-for-one, owns the peer workers, the event channel they write to, and the per-connection budget), a download supervisor (rest-for-one, owns the coordinator and its sibling children) and a session supervisor above it, which owns one download-supervisor subtree per torrent, so rest-for-one stays scoped per torrent and one flaky torrent never restarts its siblings. See [ADR-0012](docs/adr/0012-daemon-session.md).
_Avoid_: parent thread, retry loop, watchdog (lives inside the supervisor)

**Restart budget**:
The sliding-window bound on how often a supervisor may restart a child. Per-connection at the peer supervisor (each address carries its own budget window, pruned when the decision is made); per-supervisor intensity at the download supervisor (the whole coordinator subtree, charged only on coordinator-level failures, not on every peer restart) and at the session supervisor above it (a download supervisor that crashed outright). Running out of budget is the give-up point: the download fails with `:restart-budget-exhausted` instead of being restarted again.
_Avoid_: retry counter, max retries, attempt limit

**Clean shutdown**:
A SIGINT-triggered orderly stop: peer connections close, in-progress state persists through `IDiskPort`, then the JVM exits. Without it, JVM-default SIGINT kills workers mid-piece-write and loses everything since the last save. The daemon target adds SIGTERM plus periodic per-torrent saves, so a crash loses only the window since the last save.

**Daemon**:
One long-lived session process that owns every torrent across CLI invocations: it loads all torrents plus resume state at start, accepts add/remove while running, and persists on a timer and on SIGTERM. A CLI invocation is a thin client against the running daemon, never a session itself. Transmission equivalent: `tr_session` plus `transmission-daemon`.

**Session**:
One run of the client that owns several downloads: it starts every requested torrent together and keeps a failed start from taking the others down. The running belongs to the session supervisor, which gives each download its own thread and one download-supervisor subtree per torrent, so one flaky torrent never restarts its siblings. The daemon's session is the same idea made long-lived: one process owns N torrents across CLI invocations, sharing one peer port, peer id, bandwidth, and queue instance; the current `orchestration.session` record (one map per blocking CLI call) is the stepping stone toward that, not the end state. See [ADR-0012](docs/adr/0012-daemon-session.md).
_Avoid_: using "session" for a single `Download` (a `Download` is one torrent's job; a `Session` is the set of them).

**Re-announcer**:
A child of the download supervisor that issues tracker announces on swarm exhaustion (issue #22). The peers the trackers name are dialed and the download carries on; the download gives up only when the trackers name nobody. A re-announce failure triggers rest-for-one restart of the coordinator subtree.

**Watchdog**:
A child of the download supervisor that detects "connected but choking forever" (issue #23): the blocks the download receives and the peers it is connected to stop moving. Firing the watchdog crashes the coordinator subtree, so the download supervisor restarts it — rest-for-one, bounded by its restart budget. The deadline is a minute, which outlasts two optimistic-unchoke rotations: a fresh downloader has no pieces to offer, so the peers usually unchoke it only through optimistic unchoke, about every 30 seconds. The download supervisor's restart window is five minutes, so the watchdog's restarts still run out of budget.

## Peer exchange

**Handshake**:
The mandatory opening exchange of a peer connection, establishing protocol, torrent identity, and peer identity.

**Block request**:
A peer-wire message asking for one block: piece index, offset, and length.
_Avoid_: request (unqualified)

**Block**:
One network-sized byte range inside a piece. A peer-wire `Piece` message carries exactly one block's data.

**Piece availability**:
The set of pieces a peer holds, advertised as a bitfield or single-piece announcement.
_Avoid_: bitfield (the encoding, not the concept)

## Pieces and download

**Piece**:
A fixed-size chunk of torrent content, except for a possibly shorter final piece; the unit of verification.
_Avoid_: the peer-wire `Piece` message (which carries one block)

**Piece state**:
The torrent-wide snapshot of every piece as needed, in-flight, or verified.

**Endgame mode**:
The closing phase of a download, when few pieces remain and duplicate requesting beats exclusivity.

**Download**:
One single-torrent download job: torrent description, piece state, peer connections, lifecycle state, and statistics together.

**Download progress**:
A point-in-time snapshot of a download's completion, transfer totals, rate, and connected-peer count.

