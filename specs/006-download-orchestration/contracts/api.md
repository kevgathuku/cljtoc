# API Contracts: Download Orchestration

## Public Download Management API

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

## Internal Coordination API

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

## Port Interfaces

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

---

## Error Responses

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

## Usage Example

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
