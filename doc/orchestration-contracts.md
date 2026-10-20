# Download orchestration API contracts

## Public download management API

### start-download

Starts a new torrent download.

```clojure
(start-download manager torrent-path output-dir)
  → download (map with string :id derived from torrent-path, :state :downloading)
  | {:error :invalid-torrent :message string}
  | {:error :file-not-found :message string}
```

**Parameters**:
- `manager` : DownloadManager — initialized with ports
- `torrent-path` : string — path to .torrent file
- `output-dir` : string — directory for downloaded files

**Side Effects**: 
- Parses torrent file via IDiskPort
- Initiates tracker announce via INetworkPort
- Creates peer connections

---

### pause-download

Pauses an active download, closing peer connections and persisting state.

```clojure
(pause-download download)
  → {:ok paused-download}              ; :state :paused, :peers #{}
  | {:error :not-running :message string}
```

---

### resume-download

Resumes a paused or failed download from persisted state. The saved peer
set is dropped and the tracker is re-announced when a network port is
given; pieces the dead run left in flight return to :needed; the stale
:error is cleared.

```clojure
(resume-download download)
  → {:ok resumed-download}             ; :state :downloading, state reloaded from disk when a disk port is given
  | {:error :not-paused :message string}
```

---

### progress

Returns current download progress.

```clojure
(progress time-port download)
  → {:percent float              ; 0.0 to 100.0
     :pieces-complete nat-int    ; verified pieces
     :pieces-total nat-int       ; total pieces
     :bytes-downloaded nat-int  ; verified bytes
     :rate-bytes-per-sec nat-int ; run-average download rate
     :peers-connected nat-int   ; active peers
     :state keyword}            ; :downloading :paused :completed :failed
```

---

### stop-download

Stops a download and cleans up resources.

```clojure
(stop-download download)
  → stopped-download                   ; :state :idle, :peers #{}
```

---

## Session API

A session owns several downloads in one process (see [download orchestration](download-orchestration.md#sessions-and-multiple-torrents)). The session is the value; the running lives in the supervision layer.

```clojure
(session/session manager)
  → session                            ; {:manager ... :downloads {} :failures []}

(session/start-torrents session torrent-paths output-dir)
  → session                            ; good paths become downloads, bad ones failures

(session/downloads session)
  → [download ...]                     ; ordered by id

(session/failures session)
  → [{:torrent-path ... :error ... :message ...} ...]
```

`start-torrents` keys `:downloads` by each torrent's id (its basename). A path that refuses to start lands in `:failures` with the error that `start-download` returned. A path whose id another path already claimed lands in `:failures` as `:error :duplicate-id`; a path whose declared output overlaps a download already started lands there as `:error :overlapping-output`. The rest still start.

---

## Supervision API

The session supervisor owns one download-supervisor subtree per torrent (see [ADR-0012](../docs/adr/0012-daemon-session.md)).

```clojure
(session-supervisor/start manager downloads {:on-complete f})
  → session-supervisor                 ; one child per download, started, not awaited

(session-supervisor/await-all session-supervisor)
  → session-supervisor                 ; blocks until every child finished

(session-supervisor/records session-supervisor)
  → [download ...]                     ; the records that landed, ordered by id

(download-supervisor/supervise-download manager download {:opts opts})
  → download                           ; the terminal record: finished, or failed
                                       ; with :restart-budget-exhausted

(restart/supervise child {:limit n :window-ms ms :now f})
  → {:ok result}                       ; the child returned
  | {:error :restart-budget-exhausted :restarts n :last-error e}
```

`session-supervisor/start` runs each download on its own thread, so one torrent's stall never serializes the rest. `on-complete` sees each terminal record as it finishes; a failing callback lands on the record as `:session/persist-error` instead of taking the session down. A download that already failed (a refused announce) is kept as it is and not run again — only a later `torrent.resume` re-announces it.

`download-supervisor/supervise-download` prepares the run once and restarts the subtree when it crashes, while the restart budget holds. The subtree publishes its record as it progresses, so a restart resumes from the pieces already verified.

---

## Internal coordination API

### handle-piece-data

Called when peer sends piece data.

```clojure
(handle-piece-data manager peer-id piece-index offset data)
  → {:ok}
  | {:error :not-requested :message string}
  | {:error :invalid-offset :message string}
```

**Behavior**:
1. Stores block data in piece buffer
2. When piece complete: verifies SHA-1 hash
3. On success: writes to disk via IDiskPort, updates PieceState
4. On failure: requeues piece for download

---

### handle-peer-disconnect

Called when peer disconnects.

```clojure
(handle-peer-disconnect manager peer-id)
  → {:ok}
```

**Behavior**:
1. Removes peer from active set
2. Requeues in-flight pieces from that peer
3. Initiates new peer connections if below target

---

### handle-tracker-peers

Called with peer list from tracker.

```clojure
(handle-tracker-peers manager peers)
  → {:ok connected-count nat-int}
```

---

## Port interfaces

### DownloadManager

```clojure
(defrecord DownloadManager
 [network -port  ; INetworkPort
   disk-port     ; IDiskPort
   time-port     ; ITimePort
   downloads     ; {string Download}
   supervisor    ; Supervisor
   config])      ; {max-peers, request-queue-size, ...}
```

### Creating a Manager

```clojure
(defn manager
  [network-port disk-port time-port config]
  ;; Returns DownloadManager with injected ports
  )
```

### Disk writes (prepared layout)

```clojure
(prepare-output-layout [this layout output-dir])
  ;; Resolve the whole declared layout once; returns {:ok prepared})
(write-prepared-piece [this prepared output-dir piece-index bytes])
  ;; Write one verified piece through the prepared layout)
```

| Result | Method |
|---|---|
| `{:ok :written}` | `write-piece`, `write-output-piece`, `write-prepared-piece` |
| `{:ok prepared}` | `prepare-output-layout` |

---

## Error responses

All functions return consistent error format:

```clojure
{:error error-kw :message "human-readable string"}
```

Common errors:

| Error | Meaning |
|-------|---------|
| `:invalid-torrent` | .torrent file cannot be parsed |
| `:file-not-found` | Path does not exist |
| `:not-running` | Download is not active |
| `:not-paused` | Cannot resume (neither paused nor failed) |
| `:already-paused` | Download already paused |
| `:not-found` | Download ID not found |
| `:no-peers` | Cannot connect to any peers |
| `:tracker-error` | Tracker communication failed |
| `:disk-error` | Disk I/O failed |
| `:hash-mismatch` | Piece verification failed |

---

## Usage example

```clojure
(require '[dev.cljtoc.orchestration.download :as download])

;; Create manager with real ports
(def time (real-time))
(def m (download/manager (real-network) (real-disk) time {}))

;; Start
(def download (download/start-download m "test.torrent" "./downloads"))
;; => {:id "test", :state :downloading, ...}

;; Poll progress
(download/progress time download)
;; => {:percent 45.2 :pieces-complete 230 :pieces-total 512 ...}

;; Pause
(def paused (:ok (download/pause-download download)))

;; Resume
(download/resume-download paused)

;; Stop
(download/stop-download download)
```
