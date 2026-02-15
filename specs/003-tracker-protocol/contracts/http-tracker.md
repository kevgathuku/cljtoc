# HTTP Tracker Protocol Contract

**Feature**: 003-tracker-protocol
**Date**: 2026-02-15
**Protocol**: BEP 3 - The BitTorrent Protocol Specification

## Overview

This contract defines the public API for HTTP tracker protocol operations. All functions are pure and return `{:ok value}` or `{:error ...}` result maps.

## Public API

### build-http-announce-url

Build an HTTP tracker announce request URL.

**Signature**:

```clojure
(defn build-http-announce-url
  "Build HTTP tracker announce URL with properly encoded parameters.

  Parameters:
    tracker-url - Base tracker URL (e.g., 'http://tracker.example.com/announce')
    info-hash - 20-byte torrent identifier
    peer-id - 20-byte client identifier
    port - Listening port (1-65535)
    uploaded - Bytes uploaded this session
    downloaded - Bytes downloaded this session
    left - Bytes remaining to download
    opts - Optional parameters map:
      :event - :started | :completed | :stopped
      :compact - Boolean (default true)
      :num-want - Number of peers wanted (default 50)
      :no-peer-id - Boolean (default false)
      :tracker-id - Tracker ID from previous response

  Returns:
    {:ok url-string} or {:error ...}"
  [tracker-url info-hash peer-id port uploaded downloaded left opts])
```

**Preconditions**:
- `tracker-url` is valid HTTP(S) URL string
- `info-hash` is exactly 20 bytes
- `peer-id` is exactly 20 bytes
- `port` is in range 1-65535
- `uploaded`, `downloaded`, `left` are non-negative integers

**Success Result**:
```clojure
{:ok "http://tracker.example.com/announce?info_hash=%12%34...&peer_id=...&port=6881&uploaded=0&downloaded=0&left=1234567890&compact=1"}
```

**Error Results**:
```clojure
{:error :invalid-info-hash :message "Info hash must be exactly 20 bytes" :actual-length 19}
{:error :invalid-peer-id :message "Peer ID must be exactly 20 bytes" :actual-length 21}
{:error :invalid-port :message "Port must be in range 1-65535" :port 0}
{:error :invalid-tracker-url :message "Tracker URL is not a valid HTTP(S) URL"}
```

**Examples**:

```clojure
;; Basic announce
(build-http-announce-url
  "http://tracker.example.com/announce"
  (byte-array 20)  ; info-hash
  (byte-array 20)  ; peer-id
  6881
  0
  0
  1234567890
  {})
;; => {:ok "http://tracker.example.com/announce?info_hash=%00%00..."}

;; Announce with started event
(build-http-announce-url
  "http://tracker.example.com/announce"
  info-hash
  peer-id
  6881
  0
  0
  1234567890
  {:event :started :compact true :num-want 100})
;; => {:ok "http://tracker.example.com/announce?info_hash=...&event=started&compact=1&numwant=100"}
```

---

### parse-http-tracker-response

Parse an HTTP tracker response (bencode-encoded).

**Signature**:

```clojure
(defn parse-http-tracker-response
  "Parse HTTP tracker announce response.

  Parameters:
    response-bytes - HTTP response body (bencode-encoded dictionary)

  Returns:
    {:ok tracker-response} or {:error ...}"
  [response-bytes])
```

**Preconditions**:
- `response-bytes` is valid bencode-encoded data

**Success Result** (announce success):
```clojure
{:ok
 {:success true
  :protocol :http
  :response-type :announce
  :peers [{:ip "192.168.1.1" :port 6881}
          {:ip "10.0.0.5" :port 51413}]
  :interval 1800
  :min-interval 900
  :complete 15
  :incomplete 42
  :tracker-id "tracker123"}}
```

**Success Result** (tracker failure):
```clojure
{:ok
 {:success false
  :protocol :http
  :response-type :announce
  :failure-reason "Torrent not registered with this tracker"}}
```

**Error Results**:
```clojure
{:error :invalid-bencode :message "Failed to decode bencode" :position 42}
{:error :missing-required-field :message "Response missing 'interval' field"}
{:error :invalid-peer-format :message "Peers field has invalid format"}
```

**Examples**:

```clojure
;; Parse successful response with compact peers
(parse-http-tracker-response
  (bencode/encode-bencode
    {"interval" 1800
     "complete" 15
     "incomplete" 42
     "peers" (byte-array [192 168 1 1 0x1A 0xE1])})) ; 192.168.1.1:6881
;; => {:ok {:success true :peers [{:ip "192.168.1.1" :port 6881}] ...}}

;; Parse failure response
(parse-http-tracker-response
  (bencode/encode-bencode
    {"failure reason" "Torrent not found"}))
;; => {:ok {:success false :failure-reason "Torrent not found"}}
```

---

### parse-compact-peers-ipv4

Parse compact IPv4 peer list (6 bytes per peer).

**Signature**:

```clojure
(defn parse-compact-peers-ipv4
  "Parse compact IPv4 peer list.

  Parameters:
    peers-bytes - Binary peer data (6 bytes per peer)

  Returns:
    {:ok [peer-list]} or {:error ...}"
  [peers-bytes])
```

**Preconditions**:
- `peers-bytes` length is multiple of 6

**Success Result**:
```clojure
{:ok [{:ip "192.168.1.1" :port 6881}
      {:ip "10.0.0.5" :port 51413}]}
```

**Error Results**:
```clojure
{:error :invalid-length :message "Peers data length must be multiple of 6" :length 13}
{:error :invalid-ip-address :message "Invalid IPv4 address bytes"}
```

**Example**:

```clojure
;; Parse two IPv4 peers
(parse-compact-peers-ipv4
  (byte-array [192 168 1 1 0x1A 0xE1    ; 192.168.1.1:6881
               10 0 0 5 0xC8 0xF5]))      ; 10.0.0.5:51413
;; => {:ok [{:ip "192.168.1.1" :port 6881}
;;          {:ip "10.0.0.5" :port 51413}]}
```

---

### parse-compact-peers-ipv6

Parse compact IPv6 peer list (18 bytes per peer).

**Signature**:

```clojure
(defn parse-compact-peers-ipv6
  "Parse compact IPv6 peer list.

  Parameters:
    peers-bytes - Binary peer data (18 bytes per peer)

  Returns:
    {:ok [peer-list]} or {:error ...}"
  [peers-bytes])
```

**Preconditions**:
- `peers-bytes` length is multiple of 18

**Success Result**:
```clojure
{:ok [{:ip "2001:db8::1" :port 6881}
      {:ip "fe80::1" :port 51413}]}
```

**Error Results**:
```clojure
{:error :invalid-length :message "Peers data length must be multiple of 18" :length 20}
{:error :invalid-ip-address :message "Invalid IPv6 address bytes"}
```

---

### parse-dictionary-peers

Parse dictionary-format peer list (legacy format).

**Signature**:

```clojure
(defn parse-dictionary-peers
  "Parse dictionary-format peer list.

  Parameters:
    peers-list - List of dictionaries with 'ip', 'port', and optional 'peer id'

  Returns:
    {:ok [peer-list]} or {:error ...}"
  [peers-list])
```

**Preconditions**:
- `peers-list` is a sequence of maps
- Each map has `:ip` and `:port` keys

**Success Result**:
```clojure
{:ok [{:ip "192.168.1.1" :port 6881 :peer-id (byte-array 20)}
      {:ip "10.0.0.5" :port 51413}]}
```

**Error Results**:
```clojure
{:error :missing-required-field :message "Peer missing 'ip' or 'port' field" :peer {...}}
{:error :invalid-port :message "Port must be in range 1-65535" :port 70000}
```

---

## Helper Functions

### url-encode-binary

URL-encode binary data per RFC 3986.

**Signature**:

```clojure
(defn url-encode-binary
  "URL-encode binary data.

  Encodes all bytes except: A-Z a-z 0-9 . - _ ~
  All other bytes encoded as %XX where XX is hexadecimal.

  Parameters:
    data - Byte array to encode

  Returns:
    URL-encoded string"
  [data])
```

**Pure Function**: Yes (no side effects)

**Example**:

```clojure
(url-encode-binary (byte-array [0x12 0x34 0xFF]))
;; => "%12%34%FF"

(url-encode-binary (.getBytes "abc" "UTF-8"))
;; => "abc"
```

---

## Protocol Compliance

This contract implements:
- **BEP 3**: The BitTorrent Protocol Specification (HTTP tracker protocol)
- **RFC 3986**: Uniform Resource Identifier (URI) - percent-encoding

## Behavior Guarantees

1. **Purity**: All functions are pure (deterministic, no side effects)
2. **Error Handling**: All functions return result maps (no exceptions for expected failures)
3. **Binary Safety**: URL encoding preserves binary data integrity
4. **Validation**: All inputs are validated; invalid inputs return error results
5. **Immutability**: All data structures are immutable Clojure maps

## Testing Requirements

- Unit tests for all public functions
- Property-based tests for round-trip encoding (build URL → parse response)
- Test with sample tracker responses from real trackers
- Test error cases (malformed bencode, missing fields, invalid peer data)
- No network I/O in tests (use canned responses)
