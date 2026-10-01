(ns dev.cljtoc.ports.randomness
  "Randomness port protocol.

   Per ADR-0011: the randomness port is primitive — only `random-bytes`.
   Jitter math, exponential backoff, and `schedule` live in the pure
   `dev.cljtoc.domain.backoff` module; the supervisor slices (#28's
   peer-restart, #22's re-announce, #23's watchdog) consume that pure
   module, passing the port's output to the jitter function.

   Returns a byte array directly, not an envelope: byte arrays are not
   untrusted input at this seam (per ADR-0007, envelopes are reserved for
   results a caller branches on). The shape and length are spec'd."
  (:require [clojure.spec.alpha :as s]))

(s/def ::byte-count nat-int?)

(s/def ::byte-array-of-byte-count
  (s/and bytes? (s/conformer seq)
         (s/and (s/conformer seq #(let [v (seq %)] (count v))) nat-int?)))

;; A byte array of exactly the requested length. Defined as a fn-driven
  ;; spec because bytes? has no usable generator and the predicate closes
  ;; over the call site count, which a bare (s/and bytes? pred) cannot.
(defn byte-array-of-length?
  "True when `bytes` is a Java byte array of exactly `length` elements."
  [^bytes bytes length]
  (and (bytes? bytes) (= length (count bytes))))

(defn random-bytes-of-length?
  "Spec predicate: byte array of the exact length the port was asked for."
  [length]
  (fn [bytes]
    (byte-array-of-length? bytes length)))

(defprotocol IRandomnessPort
  "Abstraction for randomness effects (peer-id generation, jitter input).

   Per ADR-0011, the surface is deliberately narrow: only `random-bytes`.
   Backoff math lives in `dev.cljtoc.domain.backoff` and is shared across
   every jitter consumer (peer-restart, re-announce, watchdog), so this
   port does not need to know about time or scheduling."

  (random-bytes [this n]
    "Return a fresh byte array of exactly `n` bytes, filled from a
    cryptographically strong random source."))