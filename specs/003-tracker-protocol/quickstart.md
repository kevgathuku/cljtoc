# Quickstart: Tracker Protocol

**Feature**: 003-tracker-protocol
**Date**: 2026-02-15

## Overview

This guide shows developers how to use the tracker protocol implementation to communicate with BitTorrent trackers (HTTP and UDP) for peer discovery.

## Prerequisites

- Feature 002 (bencode-parser) must be implemented
- Basic understanding of BitTorrent protocol
- Torrent info hash and client peer ID available

## HTTP Tracker Communication

### Step 1: Build an Announce Request URL

```clojure
(require '[dev.cljtoc.protocol.tracker :as tracker])

(def info-hash (byte-array 20)) ; Your torrent's info hash
(def peer-id (byte-array 20))   ; Your client's peer ID

(def announce-url
  (tracker/build-http-announce-url
    "http://tracker.example.com:6969/announce"
    info-hash
    peer-id
    6881          ; Your listening port
    0             ; Bytes uploaded
    0             ; Bytes downloaded
    1234567890    ; Bytes left to download
    {:event :started
     :compact true
     :num-want 50}))

;; => {:ok "http://tracker.example.com:6969/announce?info_hash=%12%34...&peer_id=..."}
```

### Step 2: Make HTTP Request (via Network Port)

```clojure
;; Network I/O happens outside protocol layer
;; Use your HTTP client to GET the announce URL
;; Example with injectable network port:

(def network-port (get-network-port)) ; Inject your network implementation

(def response-bytes
  (http-get network-port (:ok announce-url)))
```

### Step 3: Parse Tracker Response

```clojure
(def parsed-response
  (tracker/parse-http-tracker-response response-bytes))

;; Success response:
;; {:ok
;;  {:success true
;;   :protocol :http
;;   :peers [{:ip "192.168.1.1" :port 6881}
;;           {:ip "10.0.0.5" :port 51413}
;;           {:ip "172.16.0.1" :port 6889}]
;;   :interval 1800
;;   :complete 15
;;   :incomplete 42}}

;; Access peer list
(when (-> parsed-response :ok :success)
  (let [peers (-> parsed-response :ok :peers)]
    (doseq [peer peers]
      (println "Peer:" (:ip peer) ":" (:port peer)))))
```

### Step 4: Schedule Next Announce

```clojure
(def current-time (System/currentTimeMillis))
(def interval (-> parsed-response :ok :interval))

(def next-announce-time
  (tracker/calculate-next-announce current-time interval))

;; => 1708023456789 (timestamp 1800 seconds in future)
```

---

## UDP Tracker Communication

### Step 1: Connect to UDP Tracker

```clojure
(require '[dev.cljtoc.protocol.tracker :as tracker])

;; Generate random transaction ID
(def txn-id (rand-int Integer/MAX_VALUE))

;; Build connect request
(def connect-request
  (tracker/build-udp-connect-request txn-id))

;; => {:ok (byte-array 16)} ; 16-byte connect request

;; Send via network port (UDP)
(def network-port (get-network-port))
(udp-send network-port "tracker.example.com" 6969 (:ok connect-request))
```

### Step 2: Parse Connect Response

```clojure
;; Receive UDP response
(def connect-response-bytes
  (udp-receive network-port 1000)) ; 1 second timeout

;; Parse connect response
(def connect-response
  (tracker/parse-udp-connect-response connect-response-bytes))

;; => {:ok
;;     {:success true
;;      :connection-id 0x123456789ABCDEF0
;;      :transaction-id txn-id}}

(def connection-id (-> connect-response :ok :connection-id))
```

### Step 3: Build Announce Request

```clojure
(def announce-txn-id (rand-int Integer/MAX_VALUE))

(def announce-request
  (tracker/build-udp-announce-request
    connection-id
    announce-txn-id
    info-hash
    peer-id
    0             ; Downloaded
    1234567890    ; Left
    0             ; Uploaded
    2             ; Event: started
    6881          ; Port
    {:num-want 50}))

;; => {:ok (byte-array 98)} ; 98-byte announce request

;; Send via network port
(udp-send network-port "tracker.example.com" 6969 (:ok announce-request))
```

### Step 4: Parse Announce Response

```clojure
;; Receive UDP response
(def announce-response-bytes
  (udp-receive network-port 1000))

;; Parse announce response
(def announce-response
  (tracker/parse-udp-announce-response announce-response-bytes))

;; => {:ok
;;     {:success true
;;      :protocol :udp
;;      :interval 1800
;;      :leechers 42
;;      :seeders 15
;;      :peers [{:ip "192.168.1.1" :port 6881}
;;              {:ip "10.0.0.5" :port 51413}]}}

;; Access peers
(def peers (-> announce-response :ok :peers))
```

---

## Error Handling

### HTTP Tracker Errors

```clojure
;; Parse tracker failure response
(def failure-response
  (tracker/parse-http-tracker-response failure-bytes))

;; => {:ok
;;     {:success false
;;      :failure-reason "Torrent not registered with this tracker"}}

;; Check for errors
(if (-> failure-response :ok :success)
  (handle-peers (-> failure-response :ok :peers))
  (log-error (-> failure-response :ok :failure-reason)))
```

### UDP Tracker Errors

```clojure
;; Parse UDP error response
(def error-response
  (tracker/parse-udp-error-response error-bytes))

;; => {:ok
;;     {:success false
;;      :protocol :udp
;;      :failure-reason "Connection ID expired"}}

;; Handle connection ID expiration
(when (str/includes? (-> error-response :ok :failure-reason) "Connection ID")
  (log-info "Reconnecting to tracker...")
  (reconnect-to-tracker))
```

### Validation Errors

```clojure
;; Invalid input returns error result
(def invalid-request
  (tracker/build-http-announce-url
    "http://tracker.example.com/announce"
    (byte-array 19)  ; Invalid: only 19 bytes (should be 20)
    peer-id
    6881
    0 0 0
    {}))

;; => {:error :invalid-info-hash
;;     :message "Info hash must be exactly 20 bytes"
;;     :actual-length 19}

;; Check for validation errors
(if (contains? invalid-request :ok)
  (use-url (:ok invalid-request))
  (log-error "Invalid request:" (:message invalid-request)))
```

---

## Complete Example: HTTP Tracker Flow

```clojure
(ns my-app.tracker-example
  (:require [dev.cljtoc.protocol.tracker :as tracker]
            [dev.cljtoc.domain.bencode :as bencode]))

(defn announce-to-http-tracker
  "Complete example: announce to HTTP tracker and get peers."
  [tracker-url info-hash peer-id port stats network-port]
  (let [;; Step 1: Build announce URL
        url-result (tracker/build-http-announce-url
                     tracker-url
                     info-hash
                     peer-id
                     port
                     (:uploaded stats)
                     (:downloaded stats)
                     (:left stats)
                     {:event :started :compact true})]

    ;; Check URL building succeeded
    (if-not (contains? url-result :ok)
      {:error "Failed to build announce URL" :details url-result}

      (let [announce-url (:ok url-result)
            ;; Step 2: Make HTTP request
            response-bytes (http-get network-port announce-url)

            ;; Step 3: Parse response
            response-result (tracker/parse-http-tracker-response response-bytes)]

        ;; Check parsing succeeded
        (if-not (contains? response-result :ok)
          {:error "Failed to parse tracker response" :details response-result}

          (let [response (:ok response-result)]
            ;; Step 4: Handle response
            (if (:success response)
              {:ok {:peers (:peers response)
                    :interval (:interval response)
                    :swarm {:seeders (:complete response)
                            :leechers (:incomplete response)}}}
              {:error "Tracker rejected announce"
               :reason (:failure-reason response)})))))))

;; Usage:
(def result
  (announce-to-http-tracker
    "http://tracker.example.com:6969/announce"
    my-info-hash
    my-peer-id
    6881
    {:uploaded 0 :downloaded 0 :left 1234567890}
    my-network-port))

;; Handle result
(when (contains? result :ok)
  (println "Got" (count (-> result :ok :peers)) "peers")
  (println "Next announce in" (-> result :ok :interval) "seconds"))
```

---

## Complete Example: UDP Tracker Flow

```clojure
(defn announce-to-udp-tracker
  "Complete example: announce to UDP tracker and get peers."
  [tracker-host tracker-port info-hash peer-id port stats network-port]
  (let [;; Step 1: Connect to get connection ID
        connect-txn-id (rand-int Integer/MAX_VALUE)
        connect-req (tracker/build-udp-connect-request connect-txn-id)
        _ (udp-send network-port tracker-host tracker-port (:ok connect-req))
        connect-resp-bytes (udp-receive network-port 5000)
        connect-resp (tracker/parse-udp-connect-response connect-resp-bytes)]

    (if-not (-> connect-resp :ok :success)
      {:error "Failed to connect to tracker" :details connect-resp}

      (let [connection-id (-> connect-resp :ok :connection-id)

            ;; Step 2: Build announce request
            announce-txn-id (rand-int Integer/MAX_VALUE)
            announce-req (tracker/build-udp-announce-request
                           connection-id
                           announce-txn-id
                           info-hash
                           peer-id
                           (:downloaded stats)
                           (:left stats)
                           (:uploaded stats)
                           2  ; Event: started
                           port
                           {:num-want 50})
            _ (udp-send network-port tracker-host tracker-port (:ok announce-req))

            ;; Step 3: Parse announce response
            announce-resp-bytes (udp-receive network-port 5000)
            announce-resp (tracker/parse-udp-announce-response announce-resp-bytes)]

        (if-not (-> announce-resp :ok :success)
          {:error "Tracker announce failed" :details announce-resp}

          {:ok {:peers (-> announce-resp :ok :peers)
                :interval (-> announce-resp :ok :interval)
                :swarm {:seeders (-> announce-resp :ok :seeders)
                        :leechers (-> announce-resp :ok :leechers)}}})))))
```

---

## Testing Without Network I/O

### Fake Network Port for Tests

```clojure
(defrecord FakeNetworkPort [responses]
  NetworkPort
  (http-get [_ url]
    ;; Return canned HTTP response
    (bencode/encode-bencode
      {"interval" 1800
       "complete" 15
       "incomplete" 42
       "peers" (byte-array [192 168 1 1 0x1A 0xE1])}))

  (udp-send [_ host port data]
    ;; No-op for tests
    nil)

  (udp-receive [_ timeout-ms]
    ;; Return canned UDP response
    (byte-array [0x00 0x00 0x00 0x01  ; action (announce)
                 0x12 0x34 0x56 0x78  ; transaction-id
                 0x00 0x00 0x07 0x08  ; interval (1800)
                 0x00 0x00 0x00 0x2A  ; leechers (42)
                 0x00 0x00 0x00 0x0F  ; seeders (15)
                 192 168 1 1 0x1A 0xE1])))

;; Use in tests
(def fake-port (->FakeNetworkPort {}))

(deftest test-http-tracker-flow
  (let [result (announce-to-http-tracker
                 "http://fake.tracker/announce"
                 (byte-array 20)
                 (byte-array 20)
                 6881
                 {:uploaded 0 :downloaded 0 :left 1000}
                 fake-port)]
    (is (contains? result :ok))
    (is (= 1 (count (-> result :ok :peers))))))
```

---

## Best Practices

1. **Always validate inputs**: Check that info hash and peer ID are exactly 20 bytes before building requests

2. **Handle both success and failure**: Tracker responses can indicate failure with `:success false`

3. **Respect announce intervals**: Wait the tracker-specified interval before re-announcing

4. **Use exponential backoff**: On tracker failures, increase delay between retries

5. **Inject dependencies**: Use ports for network I/O and time to enable pure testing

6. **Check result maps**: All functions return `{:ok ...}` or `{:error ...}` - always check which

7. **UDP connection lifecycle**: Connection IDs expire after 60 seconds - reconnect if needed

8. **Compact format**: Use `:compact true` for efficiency (saves ~80% bandwidth)

9. **IPv6 support**: Parse both IPv4 and IPv6 compact peer formats

10. **Transaction IDs**: Use random transaction IDs for UDP to prevent response spoofing

---

## Common Pitfalls

❌ **Don't use Java URLEncoder for binary data**
```clojure
;; WRONG: URLEncoder uses application/x-www-form-urlencoded (spaces as +)
(java.net.URLEncoder/encode info-hash "UTF-8")
```

✅ **Use tracker protocol's URL encoding**
```clojure
;; CORRECT: RFC 3986 percent-encoding
(tracker/url-encode-binary info-hash)
```

❌ **Don't ignore tracker failure responses**
```clojure
;; WRONG: Assumes success
(def peers (-> response :ok :peers))
```

✅ **Check success flag**
```clojure
;; CORRECT: Handle both success and failure
(if (-> response :ok :success)
  (use-peers (-> response :ok :peers))
  (log-error (-> response :ok :failure-reason)))
```

❌ **Don't reuse expired UDP connection IDs**
```clojure
;; WRONG: Connection ID expires after 60 seconds
(announce-every-5-minutes connection-id) ; Will fail after 60s
```

✅ **Reconnect when needed**
```clojure
;; CORRECT: Check for expiration errors and reconnect
(if (connection-id-expired? error-response)
  (reconnect-and-announce)
  (retry-announce))
```

---

## Next Steps

- **Feature 004 (peer-wire-protocol)**: Use discovered peers to establish connections
- **Feature 005 (piece-selection)**: Coordinate downloading pieces from multiple peers
- **Feature 006 (download-orchestration)**: Integrate tracker announces with download flow

---

## References

- [BEP 3: The BitTorrent Protocol](http://www.bittorrent.org/beps/bep_0003.html)
- [BEP 15: UDP Tracker Protocol](http://www.bittorrent.org/beps/bep_0015.html)
- [HTTP Tracker Contract](contracts/http-tracker.md)
- [UDP Tracker Contract](contracts/udp-tracker.md)
- [Data Model Documentation](data-model.md)
