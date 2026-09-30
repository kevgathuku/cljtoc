# UDP tracker protocol contract

**Feature**: 003-tracker-protocol
**Date**: 2026-02-15
**Protocol**: BEP 15 - UDP Tracker Protocol for BitTorrent

## Overview

This contract defines the public API for UDP tracker protocol operations. All functions are pure and return `{:ok value}` or `{:error ...}` result maps.

## Public API

### build-udp-connect-request

Build a UDP tracker connect request message.

**Signature**:

```clojure
(defn build-udp-connect-request
  "Build UDP tracker connect request (16 bytes).

  Parameters:
    transaction-id - 32-bit random transaction identifier

  Returns:
    {:ok byte-array} - 16-byte connect request message"
  [transaction-id])
```

**Preconditions**:
- `transaction-id` is 32-bit integer (0 to 2^32-1)

**Success Result**:
```clojure
{:ok (byte-array [0x00 0x00 0x04 0x17 0x27 0x10 0x19 0x80  ; protocol_id
                  0x00 0x00 0x00 0x00                      ; action (0)
                  0x12 0x34 0x56 0x78])}                   ; transaction_id
```

**Message Format** (16 bytes):
- Bytes 0-7: Protocol ID (magic constant `0x41727101980`)
- Bytes 8-11: Action (0 for connect)
- Bytes 12-15: Transaction ID

**Example**:

```clojure
(build-udp-connect-request 0x12345678)
;; => {:ok (byte-array 16)} ; 16-byte connect request
```

---

### parse-udp-connect-response

Parse a UDP tracker connect response message.

**Signature**:

```clojure
(defn parse-udp-connect-response
  "Parse UDP tracker connect response (16 bytes).

  Parameters:
    response-bytes - 16-byte UDP response message
    expected - optional {:action keyword :transaction-id int} the live
      request sent

  Returns:
    {:ok tracker-response} or {:error ...}"
  ([response-bytes] [response-bytes expected])
```

**Preconditions**:
- `response-bytes` is at least 16 bytes

**Success Result**:
```clojure
{:ok
 {:success true
  :protocol :udp
  :response-type :connect
  :action 0
  :transaction-id 0x12345678
  :connection-id 0x123456789ABCDEF0}}
```

**Error Results**:
```clojure
{:error :invalid-length :message "Connect response must be 16 bytes" :length 10}
{:error :invalid-action :message "Expected action 0 (connect)" :action 3}
{:error :txn-mismatch :message "Expected transaction 42, got 43" :expected 42 :actual 43}
{:error :action-mismatch :message "Expected action :connect, got :announce" :expected :connect :actual :announce}
```

**Example**:

```clojure
(parse-udp-connect-response
  (byte-array [0x00 0x00 0x00 0x00                      ; action (0)
               0x12 0x34 0x56 0x78                      ; transaction_id
               0x12 0x34 0x56 0x78 0x9A 0xBC 0xDE 0xF0])) ; connection_id
;; => {:ok {:connection-id 0x123456789ABCDEF0 ...}}
```

---

### build-udp-announce-request

Build a UDP tracker announce request message.

**Signature**:

```clojure
(defn build-udp-announce-request
  "Build UDP tracker announce request (98 bytes).

  Parameters:
    connection-id - 64-bit connection ID from connect response
    transaction-id - 32-bit random transaction identifier
    info-hash - 20-byte torrent identifier
    peer-id - 20-byte client identifier
    downloaded - Bytes downloaded this session
    left - Bytes remaining to download
    uploaded - Bytes uploaded this session
    event - Event code (0=none, 1=completed, 2=started, 3=stopped)
    port - Listening port (1-65535)
    opts - Optional parameters:
      :key - Random key for IP changes (default 0)
      :num-want - Number of peers wanted (default -1)
      :ip - IP address override (default 0 for sender IP)

  Returns:
    {:ok byte-array} - 98-byte announce request message"
  [connection-id transaction-id info-hash peer-id downloaded left uploaded event port opts])
```

**Preconditions**:
- `connection-id` is 64-bit integer
- `transaction-id` is 32-bit integer
- `info-hash` is exactly 20 bytes
- `peer-id` is exactly 20 bytes
- `downloaded`, `left`, `uploaded` are non-negative integers
- `event` is 0-3 (0=none, 1=completed, 2=started, 3=stopped)
- `port` is in range 1-65535

**Success Result**:
```clojure
{:ok (byte-array 98)} ; 98-byte announce request
```

**Error Results**:
```clojure
{:error :invalid-info-hash :message "Info hash must be exactly 20 bytes"}
{:error :invalid-peer-id :message "Peer ID must be exactly 20 bytes"}
{:error :invalid-event :message "Event must be 0-3" :event 5}
{:error :invalid-port :message "Port must be in range 1-65535"}
```

**Example**:

```clojure
(build-udp-announce-request
  0x123456789ABCDEF0  ; connection-id
  0x87654321          ; transaction-id
  (byte-array 20)     ; info-hash
  (byte-array 20)     ; peer-id
  0                   ; downloaded
  1234567890          ; left
  0                   ; uploaded
  2                   ; event (started)
  6881                ; port
  {:num-want 50})
;; => {:ok (byte-array 98)}
```

---

### parse-udp-announce-response

Parse a UDP tracker announce response message.

**Signature**:

```clojure
(defn parse-udp-announce-response
  "Parse UDP tracker announce response (20+ bytes).

  Parameters:
    response-bytes - UDP response message (20 bytes + N*6 bytes for peers)
    expected - optional {:action keyword :transaction-id int} the live
      request sent

  Returns:
    {:ok tracker-response} or {:error ...}"
  ([response-bytes] [response-bytes expected])
```

**Preconditions**:
- `response-bytes` is at least 20 bytes
- Peer data length is multiple of 6 (IPv4) or 18 (IPv6)

**Success Result**:
```clojure
{:ok
 {:success true
  :protocol :udp
  :response-type :announce
  :action 1
  :transaction-id 0x87654321
  :interval 1800
  :leechers 42
  :seeders 15
  :peers [{:ip "192.168.1.1" :port 6881}
          {:ip "10.0.0.5" :port 51413}]}}
```

**Error Results**:
```clojure
{:error :invalid-length :message "Announce response must be at least 20 bytes" :length 15}
{:error :invalid-action :message "Expected action 1 (announce)" :action 0}
{:error :invalid-peer-data :message "Peer data length must be multiple of 6" :length 13}
{:error :txn-mismatch :message "Expected transaction 42, got 43" :expected 42 :actual 43}
{:error :action-mismatch :message "Expected action :announce, got :connect" :expected :announce :actual :connect}
```

**Example**:

```clojure
(parse-udp-announce-response
  (byte-array [0x00 0x00 0x00 0x01                      ; action (1)
               0x87 0x65 0x43 0x21                      ; transaction_id
               0x00 0x00 0x07 0x08                      ; interval (1800)
               0x00 0x00 0x00 0x2A                      ; leechers (42)
               0x00 0x00 0x00 0x0F                      ; seeders (15)
               192 168 1 1 0x1A 0xE1]))                 ; peers (192.168.1.1:6881)
;; => {:ok {:interval 1800 :leechers 42 :seeders 15 :peers [...]}}
```

---

### parse-udp-error-response

Parse a UDP tracker error response message.

**Signature**:

```clojure
(defn parse-udp-error-response
  "Parse UDP tracker error response (8+ bytes).

  Parameters:
    response-bytes - UDP error response message (8 bytes + error string)

  Returns:
    {:ok tracker-response} with :success false"
  [response-bytes])
```

**Preconditions**:
- `response-bytes` is at least 8 bytes

**Success Result**:
```clojure
{:ok
 {:success false
  :protocol :udp
  :response-type :error
  :action 3
  :transaction-id 0x87654321
  :failure-reason "Torrent not found"}}
```

**Error Results**:
```clojure
{:error :invalid-length :message "Error response must be at least 8 bytes" :length 5}
{:error :invalid-action :message "Expected action 3 (error)" :action 1}
```

**Example**:

```clojure
(parse-udp-error-response
  (byte-array [0x00 0x00 0x00 0x03                      ; action (3)
               0x87 0x65 0x43 0x21                      ; transaction_id
               ...error-message-bytes...]))
;; => {:ok {:success false :failure-reason "Torrent not found"}}
```

---

### build-udp-scrape-request

Build a UDP tracker scrape request message.

**Signature**:

```clojure
(defn build-udp-scrape-request
  "Build UDP tracker scrape request (16 + N*20 bytes).

  Parameters:
    connection-id - 64-bit connection ID from connect response
    transaction-id - 32-bit random transaction identifier
    info-hashes - Vector of 20-byte torrent identifiers

  Returns:
    {:ok byte-array} - Scrape request message"
  [connection-id transaction-id info-hashes])
```

**Preconditions**:
- `connection-id` is 64-bit integer
- `transaction-id` is 32-bit integer
- `info-hashes` is sequence of 20-byte arrays

**Success Result**:
```clojure
{:ok (byte-array (+ 16 (* 20 (count info-hashes))))}
```

**Example**:

```clojure
(build-udp-scrape-request
  0x123456789ABCDEF0
  0x12345678
  [(byte-array 20) (byte-array 20)])
;; => {:ok (byte-array 56)} ; 16 + 2*20 bytes
```

---

### parse-udp-scrape-response

Parse a UDP tracker scrape response message.

**Signature**:

```clojure
(defn parse-udp-scrape-response
  "Parse UDP tracker scrape response (8 + N*12 bytes).

  Parameters:
    response-bytes - UDP scrape response message

  Returns:
    {:ok tracker-response} or {:error ...}"
  [response-bytes])
```

**Preconditions**:
- `response-bytes` is at least 8 bytes
- Scrape data length is multiple of 12

**Success Result**:
```clojure
{:ok
 {:success true
  :protocol :udp
  :response-type :scrape
  :action 2
  :transaction-id 0x12345678
  :torrents [{:seeders 15 :completed 100 :leechers 42}
             {:seeders 5 :completed 50 :leechers 10}]}}
```

**Example**:

```clojure
(parse-udp-scrape-response
  (byte-array [0x00 0x00 0x00 0x02                      ; action (2)
               0x12 0x34 0x56 0x78                      ; transaction_id
               0x00 0x00 0x00 0x0F                      ; seeders (15)
               0x00 0x00 0x00 0x64                      ; completed (100)
               0x00 0x00 0x00 0x2A]))                   ; leechers (42)
;; => {:ok {:torrents [{:seeders 15 :completed 100 :leechers 42}]}}
```

---

## Helper functions

### unsigned-short

Convert Java signed short to unsigned integer (0-65535).

**Signature**:

```clojure
(defn unsigned-short
  "Convert signed short to unsigned integer.

  Parameters:
    signed-short - Java signed short (-32768 to 32767)

  Returns:
    Unsigned integer (0 to 65535)"
  [signed-short])
```

**Pure Function**: Yes

**Example**:

```clojure
(unsigned-short (short 0x1AE1))  ; 6881
;; => 6881

(unsigned-short (short -1))
;; => 65535
```

---

### generate-transaction-id

Generate a random 32-bit transaction ID.

**Signature**:

```clojure
(defn generate-transaction-id
  "Generate random 32-bit transaction ID.

  Parameters:
    random-port - Injectable random number generator port

  Returns:
    32-bit random integer"
  [random-port])
```

**Note**: This function requires an injectable random port for testability.

---

## Protocol compliance

This contract implements:
- **BEP 15**: UDP Tracker Protocol for BitTorrent

## Message action codes

| Action | Code | Description |
|--------|------|-------------|
| Connect | 0 | Request connection ID |
| Announce | 1 | Announce torrent to tracker |
| Scrape | 2 | Query statistics for torrents |
| Error | 3 | Error response from tracker |

## Event codes

| Event | Code | Description |
|-------|------|-------------|
| None | 0 | No event (regular announce) |
| Completed | 1 | Download completed |
| Started | 2 | Download started |
| Stopped | 3 | Download stopped |

## Behavior guarantees

1. **Purity**: All parsing and building functions are pure
2. **Error Handling**: All functions return result maps
3. **Binary Safety**: All multi-byte integers are big-endian (network byte order)
4. **Validation**: All message fields are validated; invalid inputs return errors
5. **Immutability**: All data structures are immutable Clojure maps

## Connection lifecycle

```
1. build-udp-connect-request() → send via network port
2. receive response → parse-udp-connect-response()
3. Extract connection-id (valid for 60 seconds)
4. build-udp-announce-request(connection-id, ...) → send via network port
5. receive response → parse-udp-announce-response() or parse-udp-error-response()
6. If connection-id expires, return to step 1
```

## Testing requirements

- Unit tests for all public functions
- Property-based tests for round-trip encoding (build message → parse response)
- Test with sample UDP responses from real trackers
- Test error cases (invalid lengths, action mismatches, transaction ID mismatches)
- Test connection ID expiration handling
- No network I/O in tests (use canned binary responses)
- Test big-endian integer encoding/decoding
- Test unsigned short conversion (ports in range 32768-65535)
