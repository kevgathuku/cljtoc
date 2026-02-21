# Quickstart: Download Orchestration

## Getting Started

### Starting a Download

```clojure
(require '[dev.cljtoc.orchestration.download :as download]
         '[dev.cljtoc.ports.network :as network-port]
         '[dev.cljtoc.ports.disk :as disk-port]
         '[dev.cljtoc.ports.time :as time-port])

;; Create a manager with your ports
(def m (download/manager 
         (network-port/create)
         (disk-port/create)
         (time-port/create)
         {:max-peers 50
          :output-dir "./downloads"}))

;; Start downloading
(def result (download/start-download m "my-file.torrent" "./output"))

(println result)
;; => {:ok #uuid "550e8400-e29b-41d4-a716-446655440000"}
```

### Checking Progress

```clojure
(let [download-id (:ok result)]
  (download/progress m download-id))
;; => {:ok {:percent 45.2
;;          :pieces-complete 230
;;          :pieces-total 512
;;          :bytes-downloaded 120053248
;;          :rate-bytes-per-sec 524288
;;          :peers-connected 12
;;          :state :downloading}}
```

### Pause and Resume

```clojure
;; Pause - saves state to disk
(download/pause-download m download-id)
;; => {:ok {:state :paused :bytes-downloaded 120053248 :pieces-complete 230}}

;; Resume - continues from saved state
(download/resume-download m download-id)
;; => {:ok {:state :downloading :peers-connected 8}}
```

### Stop

```clojure
;; Stop and cleanup
(download/stop-download m download-id)
;; => {:ok}
```

---

## Testing

### Using Test Doubles

```clojure
(require '[dev.cljtoc.test-doubles.network :as mock-net]
         '[dev.cljtoc.test-doubles.disk :as mock-disk]
         '[dev.cljtoc.test-doubles.time :as mock-time]
         '[dev.cljtoc.orchestration.download :as download])

;; Create mock ports
(def network (mock-net/create {:peers [{:address "127.0.0.1:6881"
                                        :bitfield #{0 1 2 3 4}}]}))
(def disk (mock-disk/create {}))
(def time (mock-time/create {:now #inst "2026-01-01T00:00:00Z"}))

;; Create manager with mocks
(def m (download/manager network disk time {}))

(deftest test-download-completes
  (let [[result] (download/start-download m "test.torrent" "/tmp/out")]
    (is (:ok result))
    (let [progress (download/progress m result)]
      (is (= :completed (:state progress))))))
```

### Mock Network Responses

```clojure
(mock-net/add-response network {:type :handshake
                                :info-hash (bytes 20)
                                :peer-id (bytes 20)})

(mock-net/add-piece network 0 (byte-array 16384))
```

---

## Configuration

### Manager Config

```clojure
{:max-peers 50                    ; maximum concurrent peers
 :min-peers 5                     ; reconnect if below this
 :request-queue-size 16           ; pending requests per peer
 :piece-timeout-ms 30000         ; timeout for piece download
 :tracker-announce-interval-ms 1800000 ; re-announce every 30 min
 :output-dir "./downloads"}       ; default output directory
```

---

## Error Handling

```clojure
(let [result (download/start-download m "bad.torrent" "./out")]
  (if (:ok result)
    (println "Started")
    (case (:error result)
      :invalid-torrent (println "Bad torrent file")
      :file-not-found (println "File not found")
      (println "Unknown error:" (:message result)))))
```

---

## Integration with Existing Code

This module integrates with completed features:

```clojure
;; Feature 002: Bencode parsing (via IDiskPort)
(require '[dev.cljtoc.domain.bencode :as bencode])

;; Feature 003: Tracker protocol (via INetworkPort)
(require '[dev.cljtoc.protocol.tracker :as tracker])

;; Feature 004: Peer wire protocol (via INetworkPort)
(require '[dev.cljtoc.protocol.peer :as peer])

;; Feature 005: Piece management (pure domain)
(require '[dev.cljtoc.domain.pieces :as pieces])

;; This feature: Orchestration
(require '[dev.cljtoc.orchestration.download :as download])
```
