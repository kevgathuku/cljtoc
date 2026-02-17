# Quickstart: Peer Wire Protocol

**Feature**: 004-peer-wire-protocol  
**Get started in 5 minutes**

---

## Installation

This feature is part of the core cljtoc library. Add to your `project.clj`:

```clojure
:dev.cljtoc/protocol {:local/root "src/dev/cljtoc/protocol"}
```

Or require the namespaces directly:

```clojure
(ns my-app
  (:require [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]))
```

---

## Quick Examples

### 1. Parse a Handshake

```clojure
;; Receive 68 bytes from peer connection
(let [handshake-bytes (read-68-bytes-from-peer)
      result (peer/parse-handshake handshake-bytes)]
  (if-let [handshake (:ok result)]
    (do
      (println "Peer ID:" (:peer-id handshake))
      (println "Info Hash:" (:info-hash handshake))
      ;; Verify info-hash matches our torrent
      )
    (println "Parse failed:" (:message result))))
```

### 2. Build a Handshake

```clojure
;; Create handshake to send to peer
(let [info-hash (.getBytes "...20 bytes...")
      peer-id (.getBytes "-CL0001-...12 chars...")
      result (peer/build-handshake info-hash peer-id)]
  (when-let [bytes (:ok result)]
    ;; Send bytes to peer via TCP socket
    (send-to-peer socket bytes)))
```

### 3. Parse Peer Messages

```clojure
;; Parse a single message
(let [message-bytes (read-from-peer socket)
      result (peer/parse-message message-bytes)]
  (when-let [msg (:ok result)]
    (case (:message-type msg)
      :choke (println "Peer choked us")
      :unchoke (println "Peer unchoked us")
      :have (println "Peer has piece" (:piece-index msg))
      :piece (write-block-to-disk (:data msg))
      ;; ... handle other types
      )))

;; Parse multiple messages from buffer
(let [buffer (read-available-bytes socket)
      result (peer/parse-messages buffer)]
  (when-let [messages (:ok result)]
    (doseq [msg messages]
      (handle-message msg))
    ;; Save remaining bytes for next read
    (reset! pending-bytes (:remaining result))))
```

### 4. Build Peer Messages

```clojure
;; Send interested message
(let [{:keys [ok]} (peer/build-interested)]
  (send-to-peer socket ok))

;; Request a block
(let [piece-idx 42
      begin 0
      length 16384
      {:keys [ok]} (peer/build-request piece-idx begin length)]
  (send-to-peer socket ok))

;; Send piece data
(let [piece-idx 42
      begin 0
      data (read-block-from-disk piece-idx begin 16384)
      {:keys [ok]} (peer/build-piece piece-idx begin data)]
  (send-to-peer socket ok))
```

### 5. Manage Peer State

```clojure
;; Initialize state for a new connection
(def peer-state (atom (peer-state/initial-peer-state total-piece-count)))

;; Apply incoming messages
(defn handle-peer-message [msg]
  (swap! peer-state peer-state/apply-message msg))

;; Check if we can request pieces
(defn can-download? []
  (and (peer-state/can-request? @peer-state)
       (peer-state/peer-has-piece? @peer-state next-piece-idx)))

;; Get piece availability count
(println "Peer has" (peer-state/peer-piece-count @peer-state) "pieces")
```

---

## Common Patterns

### Pattern: Round-trip Validation

```clojure
;; Verify your encode/decode is correct
(let [original (peer/->Request 42 0 16384)
      {:keys [ok built]} (peer/build-message original)
      {:keys [ok parsed]} (peer/parse-message built)]
  (assert (= original parsed) "Round-trip failed!"))
```

### Pattern: Safe Message Handling

```clojure
(defn safe-parse [bytes]
  (let [result (peer/parse-message bytes)]
    (if (:ok result)
      result
      (do
        (log/warn "Parse error:" (:message result))
        {:error result}))))
```

### Pattern: Message Sequences

```clojure
;; Build multiple messages at once
(let [messages [(peer/->Interested)
                (peer/->Request 0 0 16384)
                (peer/->Request 0 16384 16384)]
      {:keys [ok]} (peer/build-messages messages)]
  (send-to-peer socket ok))
```

---

## Error Handling

All functions return `{:ok value}` or `{:error keyword :message string}`:

```clojure
(let [result (peer/parse-handshake bytes)]
  (cond
    (:ok result)
    (process-handshake (:ok result))
    
    (= :incomplete-handshake (:error result))
    (buffer-for-more-bytes)
    
    (= :unsupported-protocol (:error result))
    (close-connection "Wrong protocol")
    
    :else
    (log/error "Unexpected error:" result)))
```

Common errors:
- `:incomplete-handshake` - Need more bytes (buffer and retry)
- `:incomplete-message` - Need more bytes (buffer and retry)
- `:unknown-message-type` - Peer sent invalid message (close connection)
- `:invalid-input` - Programming error (check your inputs)

---

## Testing

### Pure Function Tests

```clojure
(deftest handshake-roundtrip-test
  (let [info-hash (byte-array 20 (byte 0xAB))
        peer-id (byte-array 20 (byte 0xCD))
        {:keys [ok built]} (peer/build-handshake info-hash peer-id)
        {:keys [ok parsed]} (peer/parse-handshake built)]
    (is (java.util.Arrays/equals info-hash (:info-hash parsed)))
    (is (java.util.Arrays/equals peer-id (:peer-id parsed)))))
```

### State Machine Tests

```clojure
(deftest state-transitions-test
  (let [state (peer-state/initial-peer-state 100)]
    ;; Peer chokes us
    (is (-> state
            (peer-state/apply-message (peer/->Choke))
            :peer-choking))
    
    ;; Peer unchokes us
    (is (not (-> state
                 (peer-state/apply-message (peer/->Unchoke))
                 :peer-choking)))))
```

### Generative Tests

```clojure
(use-fixtures :once schema-test/validate-schemas)

(defspec piece-message-roundtrip 100
  (prop/for-all [idx gen/nat
                 offset gen/nat
                 data (gen/such-that #(<= (count %) 16384) gen/bytes)]
    (let [msg (peer/->Piece idx offset data)
          {:keys [ok built]} (peer/build-message msg)
          {:keys [ok parsed]} (peer/parse-message built)]
      (= msg parsed))))
```

---

## Architecture Notes

### Layer Placement

Per the project constitution, this feature lives in the **Protocol Layer**:

```
Coordination Layer  ← uses this layer
       ↓
Protocol Layer      ← YOU ARE HERE (peer wire protocol)
       ↓
Domain Layer        ← uses for piece selection
```

### Design Principles

1. **Pure Functions Only**: No I/O, no side effects, no exceptions
2. **Byte Array Boundary**: Input/output is always byte arrays - TCP handling is coordination layer's job
3. **Immutable State**: PeerState is immutable, transitions return new state
4. **Explicit Errors**: All failures return error maps, never throw

### No-Go Areas

❌ **Don't do this**:
```clojure
;; DON'T: Use exceptions
(throw (Exception. "Invalid handshake"))

;; DON'T: Do I/O in protocol layer
(slurp "handshake.bin")

;; DON'T: Use global state
(def peer-state (atom nil))

;; DON'T: Mutate state
(set! (.state peer) new-value)
```

✅ **Do this instead**:
```clojure
;; DO: Return error maps
{:error :invalid-handshake :message "..."}

;; DO: Accept bytes as input
(peer/parse-handshake byte-array)

;; DO: Pass state explicitly
(peer-state/apply-message current-state message)

;; DO: Return new state
(let [new-state (peer-state/apply-message state msg)]
  ...)
```

---

## Next Steps

1. Read the full [API Contracts](contracts/api.md)
2. Review [Data Model](data-model.md)
3. Check out [Research Notes](research.md) for design decisions
4. See parent architecture: [001-clojure-bittorrent-client](../001-clojure-bittorrent-client/spec.md)

---

## Troubleshooting

**Q: My handshake parse fails with `:incomplete-handshake`**  
A: You need exactly 68 bytes. TCP is stream-based - buffer bytes until you have enough.

**Q: Round-trip test fails for Piece message**  
A: Ensure data is exactly the same. Byte arrays require `java.util.Arrays/equals`, not `=`.

**Q: State transitions not working**  
A: Remember to use the return value: `(swap! state peer-state/apply-message msg)` not `(peer-state/apply-message @state msg)`

**Q: Block size validation fails**  
A: Maximum block size is 16384 bytes (16 KiB). Larger blocks violate BEP 3.
