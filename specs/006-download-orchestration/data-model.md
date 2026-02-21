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
├── id : uuid                  (unique identifier)
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
  (read-torrent [this path] "Parse .torrent file")
  (read-piece [this piece-index] "Read cached piece data")
  (write-piece [this piece-index bytes] "Write verified piece to disk")
  (ensure-directory [this path] "Create directory if missing")
  (save-state [this download] "Persist download state")
  (load-state [this id] "Load persisted state"))
```

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
