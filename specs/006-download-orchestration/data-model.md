# Data Model: End-to-End Single Torrent Download

## Core Records

### TorrentMetadata

Represents parsed .torrent file content. Provided by feature 002 (bencode parser).

```
TorrentMetadata
├── info-hash : bytes          (20-byte SHA-1 of info dict)
├── name : string              (torrent name)
├── piece-length : nat-int    (bytes per piece)
├── pieces : bytes             (concatenated 20-byte SHA-1 hashes)
├── length : nat-int           (total bytes, for single-file)
└── files : [{:path [string]   (for multi-file torrents)
               :length nat-int}]
```

### Download

Main orchestration record. Created when download starts, holds all runtime state.

```
Download
├── id : string                (human-readable, derived from torrent path; uuid before issue #7)
├── torrent : TorrentMetadata  (immutable torrent info)
├── piece-state : PieceState   (from feature 005)
├── peers : #{Peer}            (active connections)
├── state : keyword            (:idle :starting :downloading :paused :completed :failed)
├── output-dir : string        (where files are written)
├── stats : DownloadStats      (runtime statistics)
└── error : ErrorInfo          (present when state = :failed)
```

### Peer

Represents an active peer connection.

```
Peer
├── id : string                (unique: "ip:port")
├── address : string            (peer IP:port)
├── port : nat-int             (peer port)
├── bitfield : #{piece-index}  (pieces peer has)
├── am-choking : boolean       (we are choking this peer)
├── am-interested : boolean    (we are interested in this peer)
├── peer-choking : boolean     (peer is choking us)
├── peer-interested : boolean  (peer is interested in us)
├── downloaded : nat-int       (bytes received)
└── uploaded : nat-int         (bytes sent)
```

### DownloadStats

Runtime statistics for progress reporting.

```
DownloadStats
├── started-at : instant       (when download started)
├── completed-at : instant    (when completed, if applicable)
├── bytes-downloaded : nat-int (total bytes verified)
├── bytes-uploaded : nat-int   (total bytes sent, for seeding)
└── last-update : instant      (for rate calculation)
```

### ErrorInfo

Error details when download fails.

```
ErrorInfo
├── reason : keyword           (:no-peers :tracker-error :all-pieces-failed :disk-error)
├── message : string           (human-readable)
└── failed-piece : nat-int     (if applicable)
```

## Port Protocols

### INetworkPort

Network effect abstraction.

```clojure
(defprotocol INetworkPort
  (connect [this address] "Open TCP connection to peer")
  (send-message [this peer message] "Send peer wire message")
  (receive-message [this peer] "Receive next message from peer")
  (close [this peer] "Close connection"))
```

### IDiskPort

Disk effect abstraction.

```clojure
(defprotocol IDiskPort
  (read-torrent-file [this path] "Parse .torrent file")
  (read-piece [this piece-index] "Read cached piece data")
  (write-piece [this piece-index bytes] "Write verified piece to the piece cache")
  (write-output-piece [this layout output-dir piece-index bytes]
    "Write one verified piece into the torrent file layout under output-dir")
  (initialize-output-layout [this layout output-dir]
    "Create every declared output path at its declared length, including zero-length files")
  (ensure-directory [this path] "Create directory if missing")
  (save-state [this download] "Persist download state")
  (load-state [this id] "Load persisted download state")
  (delete-state [this id] "Delete persisted download state"))
```

`layout` is the compiled output layout for the download
(`domain.torrent/compile-output-layout`: entries with cumulative byte
`:start`s, the `:sizes` map, content `:total`, `:piece-length`),
derived once per download by `run-download` — which fails `:disk-error`
on a compile failure before dialing — and threaded through to every
port call, so the per-piece path pays O(log files) span lookup instead
of re-deriving the layout per piece.

#### Result semantics

Every method returns a channel delivering exactly one envelope.

| Envelope | Delivered by |
|----------|--------------|
| `{:ok metadata}` | `read-torrent-file` |
| `{:ok bytes}` or `{:ok nil}` | `read-piece` (nil when not cached) |
| `{:ok :written}` | `write-piece`, `write-output-piece` |
| `{:ok :initialized}` | `initialize-output-layout` |
| `{:ok :created}` | `ensure-directory` |
| `{:ok :saved}` | `save-state` |
| `{:ok download}` or `{:ok nil}` | `load-state` (nil when no state exists) |
| `{:ok :deleted}` | `delete-state` |
| `{:error reason :message msg}` | any of the above, on failure |

Error reasons emitted by the reference implementation: `:file-not-found`,
`:invalid-torrent`, `:read-error`, `:write-error`, `:invalid-info`,
`:unsafe-path`, `:unsafe-output-dir`, `:mkdir-error`, `:save-error`,
`:load-error`, `:delete-error`.

Persisted state must survive a round trip: records are stored as plain maps
and byte arrays as `{:cljtoc/bytes hex}` tagged maps, so a resumed download
carries real bytes into handshake and verification
(`ports.disk/encode-state` / `decode-state`).

#### Output-path containment

`info` is torrent-controlled, so the layout compile MUST treat every
declared path as hostile (refusing `..`/empty/separator-bearing
components with `{:error :invalid-info}`), and both output methods MUST
treat the filesystem as hostile:

- Resolve symlinks and require the canonical file path to stay under the
  canonical `output-dir` — return `{:error :unsafe-path}` before opening.
  Lexical component checks alone do not stop a pre-existing symlink inside
  the target directory from redirecting the write outside it. The prefix
  comparison must not double the separator: a canonical `output-dir` that is
  itself the filesystem root already ends in one.
- Refuse an `output-dir` that is the filesystem root — return
  `{:error :unsafe-output-dir}` before creating or opening anything. This is
  policy rather than containment: declared components are validated either
  way, but a caller passing `/` almost always means an explicit directory,
  and scattering files across the root risks overwriting unrelated system
  paths. Without it the failure is a `:write-error` that depends on whether
  the process happens to be permitted to write there.

`initialize-output-layout` runs once at download start, *before* the
completion check, so a torrent with no pieces still materializes its
declared files. `run-download` compiles the layout before reaching the
port and treats either a compile or an init failure as a `:disk-error`
that fails the download rather than reporting completion.

### ITimePort

Time effect abstraction.

```clojure
(defprotocol ITimePort
  (now [this] "Current wall-clock time")
  (monotonic [this] "Milliseconds since process start")
  (set-timeout [this ms value] "Channel that closes after ms"))
```

## State Transitions

```
       +--------+
       | :idle  |
       +--------+
            | start-download
            v
       +-----------+
       | :starting |
       +-----------+
            | peers connected
            v
    +---------------+
    | :downloading  |<------------------+
    +---------------+                   |
         |    |    |                   |
         |    |    +--- pause ---------+
         |    |    |                   |
         |    |    v                   |
         |    | +-------+    resume    |
         |    +-+ :paused-+------------+
         |      +-------+               |
         |                               |
    complete                        disconnect/all
         |                               |
         v                               v
  +------------+                 +----------+
  | :completed |                 | :failed  |
  +------------+                 +----------+
```

## Validation Rules

| Field | Rule |
|-------|------|
| Download.state | Must be valid keyword from state set |
| Download.peers | Each peer must have valid bitfield |
| PieceState | Invariant: needed + in-flight + verified = total-pieces |
| Peer.bitfield | All indices must be < total-pieces |

## Relationships

```
Download 1--1 TorrentMetadata
Download 1--1 PieceState
Download *--* Peer
Download 1--1 DownloadStats
Download 0--1 ErrorInfo
```
