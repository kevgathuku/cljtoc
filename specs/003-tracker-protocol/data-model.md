# Data Model: Tracker Protocol

**Feature**: 003-tracker-protocol
**Date**: 2026-02-15

## Overview

This document defines the data structures used in tracker protocol communication. All structures are immutable Clojure maps representing tracker requests, responses, peers, and scheduling information.

## Core Entities

### TrackerRequest

Represents a request to be sent to a tracker (HTTP or UDP).

**Structure**:

```clojure
{:protocol :http | :udp
 :request-type :connect | :announce | :scrape

 ;; Common fields (HTTP and UDP announce)
 :info-hash bytes            ; 20-byte torrent identifier
 :peer-id bytes              ; 20-byte client identifier
 :port integer               ; Listening port (1-65535)
 :uploaded integer           ; Bytes uploaded this session
 :downloaded integer         ; Bytes downloaded this session
 :left integer               ; Bytes remaining to download

 ;; Optional announce fields
 :event :started | :completed | :stopped | nil
 :compact boolean            ; Use compact peer format (default true)
 :num-want integer           ; Number of peers wanted (default 50)
 :no-peer-id boolean         ; Omit peer IDs in response (default false)

 ;; UDP-specific fields
 :connection-id long         ; 64-bit connection ID (UDP only)
 :transaction-id integer     ; Random transaction ID (UDP only)
 :key integer                ; Random key for IP changes (UDP only)

 ;; HTTP-specific fields
 :tracker-id string          ; Tracker ID from previous response (HTTP only)
 :tracker-url string}        ; Tracker announce URL (HTTP only)
```

**Validation Rules**:
- `:info-hash` MUST be exactly 20 bytes
- `:peer-id` MUST be exactly 20 bytes
- `:port` MUST be in range 1-65535
- `:uploaded`, `:downloaded`, `:left` MUST be non-negative
- `:connection-id` required for UDP announce/scrape
- `:tracker-url` required for HTTP requests

**State Transitions**: Immutable (no transitions)

---

### TrackerResponse

Represents a parsed response from a tracker.

**Structure**:

```clojure
{:success boolean            ; true if announce succeeded, false for errors

 ;; Success response fields
 :peers [{:ip string :port integer :peer-id bytes}]
 :interval integer           ; Seconds until next announce
 :min-interval integer       ; Minimum announce interval (optional)
 :complete integer           ; Number of seeders
 :incomplete integer         ; Number of leechers
 :tracker-id string          ; Tracker ID for future requests (HTTP, optional)
 :connection-id long         ; Connection ID for future requests (UDP, optional)
 :warning-message string     ; Warning from tracker (optional)

 ;; Failure response fields
 :failure-reason string      ; Error message from tracker

 ;; Metadata
 :protocol :http | :udp
 :response-type :connect | :announce | :scrape
 :transaction-id integer}    ; Transaction ID (UDP only)
```

**Validation Rules**:
- If `:success` is `true`, `:peers` and `:interval` MUST be present
- If `:success` is `false`, `:failure-reason` MUST be present
- `:interval` MUST be positive integer (seconds)
- `:complete` and `:incomplete` MUST be non-negative
- UDP connection response: `:connection-id` MUST be present

**Relationships**:
- Contains list of `Peer` entities
- Derived from `TrackerRequest` (same transaction ID for UDP)

---

### Peer

Represents a single peer received from tracker.

**Structure**:

```clojure
{:ip string                  ; IPv4 address (e.g., "192.168.1.1")
                             ; or IPv6 address (e.g., "2001:db8::1")
 :port integer               ; Port number (1-65535)
 :peer-id bytes}             ; 20-byte peer identifier (optional, not in compact)
```

**Validation Rules**:
- `:ip` MUST be valid IPv4 or IPv6 address
- `:port` MUST be in range 1-65535
- `:peer-id` is optional (omitted in compact format)

**Relationships**:
- Contained in `TrackerResponse` peers list

---

### AnnounceSchedule

Represents timing information for tracker announces.

**Structure**:

```clojure
{:next-announce-time long    ; Timestamp (ms since epoch) for next announce
 :interval-seconds integer   ; Current interval in seconds
 :retry-attempt integer      ; Number of consecutive failures (0 = no failures)
 :backoff-delay-ms long}     ; Current backoff delay in milliseconds
```

**Validation Rules**:
- `:next-announce-time` MUST be future timestamp or 0 (immediate)
- `:interval-seconds` MUST be positive
- `:retry-attempt` MUST be non-negative
- `:backoff-delay-ms` MUST be non-negative

**State Transitions**:
- **Successful announce**: Reset `:retry-attempt` to 0, update `:next-announce-time`
- **Failed announce**: Increment `:retry-attempt`, calculate exponential `:backoff-delay-ms`

---

### TrackerError

Represents an error that occurred during tracker communication.

**Structure**:

```clojure
{:error-type :network | :protocol | :tracker-failure | :timeout
 :message string             ; Human-readable error message
 :tracker-url string         ; Tracker URL (if applicable)
 :timestamp long             ; When error occurred (ms since epoch)
 :context map}               ; Additional error context (optional)
```

**Error Type Semantics**:
- `:network`: Network-level failure (connection refused, host unreachable)
- `:protocol`: Protocol violation (malformed response, invalid message)
- `:tracker-failure`: Tracker returned failure reason
- `:timeout`: Request timed out waiting for response

**Validation Rules**:
- `:error-type` MUST be one of the defined types
- `:message` MUST be non-empty string
- `:timestamp` MUST be valid timestamp

---

## Protocol-Specific Data

### HTTP Tracker URL Components

HTTP tracker announce URL structure:

```
http://tracker.example.com:6969/announce?info_hash=%12%34...&peer_id=...&port=6881&uploaded=0&downloaded=0&left=1234567890&compact=1&event=started
```

**Query Parameters**:

| Parameter | Type | Required | Encoding |
|-----------|------|----------|----------|
| `info_hash` | 20 bytes | Yes | URL-encoded binary |
| `peer_id` | 20 bytes | Yes | URL-encoded binary |
| `port` | integer | Yes | Decimal string |
| `uploaded` | integer | Yes | Decimal string |
| `downloaded` | integer | Yes | Decimal string |
| `left` | integer | Yes | Decimal string |
| `compact` | 0 or 1 | No | Decimal (default 1) |
| `event` | string | No | "started", "completed", "stopped" |
| `numwant` | integer | No | Decimal (default 50) |
| `no_peer_id` | 0 or 1 | No | Decimal (default 0) |
| `trackerid` | string | No | Tracker ID from previous response |

---

### UDP Tracker Message Formats

#### Connect Request (16 bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       protocol_id (high)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       protocol_id (low)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          action (0)                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       transaction_id                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**Fields**:
- `protocol_id`: 64-bit magic constant `0x41727101980`
- `action`: 32-bit integer, `0` for connect
- `transaction_id`: 32-bit random integer

---

#### Connect Response (16 bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          action (0)                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       transaction_id                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      connection_id (high)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      connection_id (low)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**Fields**:
- `action`: 32-bit integer, `0` for connect
- `transaction_id`: Must match request
- `connection_id`: 64-bit identifier for subsequent requests (valid 60 seconds)

---

#### Announce Response (20+ bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          action (1)                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       transaction_id                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          interval                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          leechers                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          seeders                              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      peers (6 bytes each)                     |
|                             ...                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**Fields**:
- `action`: 32-bit integer, `1` for announce
- `transaction_id`: Must match request
- `interval`: 32-bit integer, seconds until next announce
- `leechers`: 32-bit integer, number of incomplete peers
- `seeders`: 32-bit integer, number of complete peers
- `peers`: N * 6 bytes (IPv4) or N * 18 bytes (IPv6)

---

### Compact Peer Formats

#### IPv4 Compact Format (6 bytes per peer)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         IPv4 address                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|              port             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**Example**: IP `192.168.1.1:6881` → `[0xC0 0xA8 0x01 0x01 0x1A 0xE1]`

---

#### IPv6 Compact Format (18 bytes per peer)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                       IPv6 address (128 bits)                 |
|                                                               |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|              port             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

---

## Entity Relationships

```
TrackerRequest
    ↓
[Network I/O via TrackerNetworkPort]
    ↓
TrackerResponse
    ├── peers: [Peer, Peer, ...]
    └── interval → AnnounceSchedule

On Success:
    TrackerResponse → AnnounceSchedule (schedule next announce)

On Failure:
    TrackerError → AnnounceSchedule (schedule retry with backoff)
```

---

## Validation Functions

All entities should have corresponding validation functions:

```clojure
(defn valid-tracker-request? [request]
  "Returns true if request is well-formed")

(defn valid-tracker-response? [response]
  "Returns true if response is well-formed")

(defn valid-peer? [peer]
  "Returns true if peer has valid IP and port")

(defn valid-announce-schedule? [schedule]
  "Returns true if schedule has valid timestamps and intervals")
```

---

## Immutability Contract

All data structures are immutable Clojure maps. Functions that "update" entities return new instances:

```clojure
;; Updating schedule after successful announce
(defn update-schedule-success [schedule interval-seconds current-time]
  (assoc schedule
         :next-announce-time (+ current-time (* interval-seconds 1000))
         :interval-seconds interval-seconds
         :retry-attempt 0
         :backoff-delay-ms 0))

;; Updating schedule after failed announce
(defn update-schedule-failure [schedule current-time]
  (let [attempt (inc (:retry-attempt schedule))
        backoff (calculate-backoff-delay attempt)]
    (assoc schedule
           :next-announce-time (+ current-time backoff)
           :retry-attempt attempt
           :backoff-delay-ms backoff)))
```

---

## Summary

This data model provides:
- **Clear entity boundaries**: Each entity has a single responsibility
- **Type safety**: Validation functions ensure data integrity
- **Immutability**: All structures are immutable maps
- **Protocol separation**: HTTP and UDP differences captured in fields
- **Pure transformations**: State changes are explicit function calls returning new instances
