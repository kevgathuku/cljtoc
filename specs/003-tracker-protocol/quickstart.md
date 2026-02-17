# Quickstart: Tracker Protocol

**Feature**: 003-tracker-protocol
**Namespace**: `dev.cljtoc.protocol.tracker`

## Overview

Pure functions for building and parsing BitTorrent tracker messages (HTTP and UDP). All functions return `{:ok value}` or `{:error ...}` — no exceptions for expected failures. Network I/O is intentionally excluded; use injectable ports for that.

## Prerequisites

- Feature 002 (bencode-parser) must be available
- `[dev.cljtoc.protocol.tracker :as tracker]` in your namespace require

---

## HTTP Tracker

### Build an Announce URL

```clojure
(require '[dev.cljtoc.protocol.tracker :as tracker])

(def info-hash (byte-array 20))  ; 20-byte torrent identifier
(def peer-id   (byte-array 20))  ; 20-byte client identifier

(tracker/build-http-announce-url
  "http://tracker.example.com:6969/announce"
  {:info-hash  info-hash
   :peer-id    peer-id
   :port       6881
   :uploaded   0
   :downloaded 0
   :left       1234567890
   :event      :started   ; optional — :started | :completed | :stopped
   :compact    true       ; optional, defaults to true
   :num-want   50})       ; optional
;; => {:ok "http://tracker.example.com:6969/announce?info_hash=%00%00...&peer_id=...&port=6881&..."}
```

Required keys: `:info-hash`, `:peer-id`, `:port`, `:uploaded`, `:downloaded`, `:left`.
Optional keys: `:event`, `:compact`, `:num-want`, `:no-peer-id`, `:tracker-id`.

If the tracker URL already has query parameters the function appends with `&` automatically.

### Parse a Tracker Response

```clojure
;; response-bytes = raw HTTP response body from the tracker
(tracker/parse-http-tracker-response response-bytes)

;; Success:
;; {:ok {:success     true
;;       :protocol    :http
;;       :response-type :announce
;;       :peers       [{:ip "192.168.1.1" :port 6881}
;;                     {:ip "10.0.0.5"   :port 51413}]
;;       :interval    1800
;;       :complete    15
;;       :incomplete  42}}

;; Tracker failure (torrent not registered, etc.):
;; {:ok {:success        false
;;       :protocol       :http
;;       :response-type  :announce
;;       :failure-reason "Torrent not registered with this tracker"}}
```

Handles both compact binary peer format (IPv4 and IPv6) and legacy dictionary format automatically.

---

## UDP Tracker (BEP 15)

### 1. Build a Connect Request

```clojure
(def txn-id (rand-int Integer/MAX_VALUE))

(tracker/build-udp-connect-request {:transaction-id txn-id})
;; => {:ok <byte-array, 16 bytes>}
;; Send these bytes to the tracker via UDP, then parse the response:
```

### 2. Parse the Connect Response

```clojure
(tracker/parse-udp-connect-response connect-response-bytes)
;; => {:ok {:action         :connect
;;          :transaction-id txn-id
;;          :connection-id  4497486125440}}
;; Save :connection-id — needed for the announce request.
```

### 3. Build an Announce Request

```clojure
(tracker/build-udp-announce-request
  {:connection-id  (-> connect-result :ok :connection-id)
   :transaction-id (rand-int Integer/MAX_VALUE)
   :info-hash      info-hash    ; 20-byte array
   :peer-id        peer-id      ; 20-byte array
   :downloaded     0
   :left           1234567890
   :uploaded       0
   :port           6881
   :event          :started     ; optional
   :num-want       50})         ; optional, defaults to -1 (no preference)
;; => {:ok <byte-array, 98 bytes>}
```

### 4. Parse the Announce Response

```clojure
(tracker/parse-udp-announce-response announce-response-bytes)
;; => {:ok {:action         :announce
;;          :transaction-id 99
;;          :interval       1800
;;          :leechers       42
;;          :seeders        15
;;          :peers          [{:ip "192.168.1.1" :port 6881}
;;                           {:ip "10.0.0.5"   :port 51413}]}}
```

### Build a Scrape Request

```clojure
(tracker/build-udp-scrape-request
  {:connection-id  connection-id
   :transaction-id (rand-int Integer/MAX_VALUE)
   :info-hashes    [info-hash-1 info-hash-2]})  ; 1+ 20-byte arrays
;; => {:ok <byte-array, 16 + N*20 bytes>}
```

### Parse a Scrape Response

```clojure
(tracker/parse-udp-scrape-response scrape-response-bytes)
;; => {:ok {:action         :scrape
;;          :transaction-id 77
;;          :torrents       [{:seeders 100 :completed 500 :leechers 20}]}}
```

### Parse a UDP Error Response

```clojure
(tracker/parse-udp-error-response error-response-bytes)
;; => {:ok {:action         :error
;;          :transaction-id 55
;;          :success        false
;;          :failure-reason "Connection ID expired"}}
```

---

## Re-Announce Timing (US6)

### Calculate Next Announce Time

```clojure
(def now (System/currentTimeMillis))

;; Uses min-interval when present, otherwise interval, otherwise default 1800s
(tracker/calculate-next-announce now 1800 nil)
;; => {:ok 1739839256789}   ; now + 1800000ms

(tracker/calculate-next-announce now 1800 300)
;; => {:ok 1739837756789}   ; uses min-interval (300s) — now + 300000ms

(tracker/calculate-next-announce now nil nil)
;; => {:ok 1739839256789}   ; default 1800s
```

### Exponential Backoff for Retries

```clojure
(tracker/calculate-exponential-backoff 1)  ;; => {:ok 1000}    ; 1s
(tracker/calculate-exponential-backoff 2)  ;; => {:ok 2000}    ; 2s
(tracker/calculate-exponential-backoff 3)  ;; => {:ok 4000}    ; 4s
(tracker/calculate-exponential-backoff 4)  ;; => {:ok 8000}    ; 8s
(tracker/calculate-exponential-backoff 20) ;; => {:ok 3600000} ; capped at 1 hour
```

### Update Schedule After Success

```clojure
(def schedule {:next-announce-time 0
               :interval-seconds   1800
               :retry-attempt      3     ; resets to 0
               :backoff-delay-ms   4000})

(tracker/update-schedule-success schedule 900 (System/currentTimeMillis))
;; => {:ok {:next-announce-time <now + 900000ms>
;;          :interval-seconds   900
;;          :retry-attempt      0      ; reset
;;          :backoff-delay-ms   0}}    ; reset
```

### Update Schedule After Failure

```clojure
(def schedule {:next-announce-time 0
               :interval-seconds   1800
               :retry-attempt      2
               :backoff-delay-ms   2000})

(tracker/update-schedule-failure schedule (System/currentTimeMillis))
;; => {:ok {:next-announce-time <now + 4000ms>  ; backoff for attempt 3
;;          :interval-seconds   1800
;;          :retry-attempt      3               ; incremented
;;          :backoff-delay-ms   4000}}
```

---

## Error Handling

Every function returns `{:ok ...}` on success or `{:error ...}` on failure.

### Error Map Shape

```clojure
;; All error maps satisfy ::spec/tracker-error:
{:error       :invalid-input      ; keyword — see below for all types
 :message     "Human-readable description"
 :spec-explain {...}}             ; present when spec validation failed
```

**Error keywords by source:**

| Keyword | Source |
|---|---|
| `:invalid-input` | Spec validation failure (bad info-hash length, etc.) |
| `:invalid-peer-data` | Compact peer bytes not a multiple of 6 or 18 |
| `:invalid-peer-format` | Dictionary peers missing `:ip` or `:port` |
| `:invalid-action-code` | UDP response has wrong action byte |
| `:invalid-message-length` | UDP response is too short |
| `:invalid-ip-address` | IP bytes cannot be parsed |

### Handling Validation Errors

```clojure
;; Wrong info-hash length → :invalid-input
(tracker/build-http-announce-url
  "http://tracker.example.com/announce"
  {:info-hash  (byte-array 10)   ; should be 20 bytes
   :peer-id    peer-id
   :port       6881
   :uploaded   0 :downloaded 0 :left 0})
;; => {:error :invalid-input
;;     :message "Input validation failed for :dev.cljtoc.protocol.tracker.spec/tracker-request"
;;     :spec-explain {...}}

;; Pattern for all callers:
(let [result (tracker/build-http-announce-url url request)]
  (if (contains? result :ok)
    (use-url (:ok result))
    (log-error (:error result) (:message result))))
```

---

## Spec Instrumentation (Development/Test)

```clojure
(require '[dev.cljtoc.protocol.tracker.spec :as spec])

;; Enable runtime argument checking on all tracker functions
(spec/instrument-tracker!)

;; Disable when done
(spec/unstrument-tracker!)
```

---

## URL Encoding Utility

```clojure
;; RFC 3986 percent-encoding for binary data (info-hash, peer-id)
(tracker/url-encode-binary (byte-array [0x12 0xAB 0xFF]))
;; => "%12%AB%FF"

(tracker/url-encode-binary (.getBytes "abc"))
;; => "abc"   ; unreserved chars A-Z a-z 0-9 . - _ ~ are preserved
```

Do not use `java.net.URLEncoder` — it encodes spaces as `+` which is wrong for binary data.

---

## Complete HTTP Flow Example

```clojure
(ns my-app.tracker
  (:require [dev.cljtoc.protocol.tracker :as tracker]))

(defn announce!
  "Announce to an HTTP tracker. network-port is an injectable I/O port."
  [tracker-url info-hash peer-id port stats network-port]
  (let [url-result (tracker/build-http-announce-url
                     tracker-url
                     {:info-hash  info-hash
                      :peer-id    peer-id
                      :port       port
                      :uploaded   (:uploaded stats)
                      :downloaded (:downloaded stats)
                      :left       (:left stats)
                      :event      (:event stats)})]
    (if-not (contains? url-result :ok)
      url-result  ; propagate validation error
      (let [response-bytes (http-get network-port (:ok url-result))
            parsed         (tracker/parse-http-tracker-response response-bytes)]
        (if-not (contains? parsed :ok)
          parsed  ; propagate parse error
          (let [resp (:ok parsed)]
            (if (:success resp)
              {:ok {:peers    (:peers resp)
                    :interval (:interval resp)
                    :seeders  (:complete resp)
                    :leechers (:incomplete resp)}}
              {:error :tracker-failure
               :message (:failure-reason resp)})))))))
```

## Complete UDP Flow Example

```clojure
(defn udp-announce!
  "Full UDP tracker cycle: connect → announce. Returns peers on success."
  [host port info-hash peer-id client-port stats network-port]
  (let [;; 1. Connect
        txn-id       (rand-int Integer/MAX_VALUE)
        connect-req  (tracker/build-udp-connect-request {:transaction-id txn-id})
        _            (udp-send network-port host port (:ok connect-req))
        connect-resp (tracker/parse-udp-connect-response
                       (udp-receive network-port 5000))]
    (if-not (contains? connect-resp :ok)
      connect-resp
      (let [conn-id (:connection-id (:ok connect-resp))

            ;; 2. Announce
            ann-req  (tracker/build-udp-announce-request
                       {:connection-id  conn-id
                        :transaction-id (rand-int Integer/MAX_VALUE)
                        :info-hash      info-hash
                        :peer-id        peer-id
                        :downloaded     (:downloaded stats)
                        :left           (:left stats)
                        :uploaded       (:uploaded stats)
                        :port           client-port
                        :event          :started})
            _        (udp-send network-port host port (:ok ann-req))
            ann-resp (tracker/parse-udp-announce-response
                       (udp-receive network-port 5000))]
        (if (contains? ann-resp :ok)
          {:ok {:peers    (-> ann-resp :ok :peers)
                :interval (-> ann-resp :ok :interval)}}
          ann-resp)))))
```

---

## Testing Without Network I/O

All tracker functions are pure — pass byte arrays in, get maps out. For integration tests, build response bytes directly:

```clojure
(deftest http-tracker-parse-test
  (let [response-bytes (bencode/encode-bencode
                         {"interval" 1800
                          "complete" 5
                          "incomplete" 10
                          "peers" (byte-array [192 168 1 1 0x1A 0xE1])})
        result (tracker/parse-http-tracker-response response-bytes)]
    (is (= true  (-> result :ok :success)))
    (is (= 1800  (-> result :ok :interval)))
    (is (= 1     (count (-> result :ok :peers))))
    (is (= "192.168.1.1" (-> result :ok :peers first :ip)))))

(deftest udp-connect-parse-test
  (let [buf   (doto (java.nio.ByteBuffer/allocate 16)
                (.putInt 0) (.putInt 42) (.putLong 0x41727101980))
        result (tracker/parse-udp-connect-response (.array buf))]
    (is (= :connect (-> result :ok :action)))
    (is (= 42       (-> result :ok :transaction-id)))))
```

---

## Next Steps

- **Feature 004** (peer-wire-protocol) — connect to discovered peers
- **Feature 005** (piece-selection) — coordinate piece requests across peers
- **Feature 006** (download-orchestration) — integrate announces with download lifecycle

## References

- [BEP 3: BitTorrent Protocol](http://www.bittorrent.org/beps/bep_0003.html)
- [BEP 15: UDP Tracker Protocol](http://www.bittorrent.org/beps/bep_0015.html)
- [HTTP Tracker Contract](contracts/http-tracker.md)
- [UDP Tracker Contract](contracts/udp-tracker.md)
- [Data Model](data-model.md)
- [Spec fdef Usage](fdef-usage.md)
