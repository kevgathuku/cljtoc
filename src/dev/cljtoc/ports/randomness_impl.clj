(ns dev.cljtoc.ports.randomness-impl
  "Real `IRandomnessPort` implementation backed by `java.security.SecureRandom`.

   `SecureRandom` is a system-seeded CSPRNG, so the per-call draw is
   independent of every other call and a fresh `SecureRandom` per port
   instance still gives globally-distributed bytes. There is no shared
   mutable state across ports; each `create` returns its own."
  (:require [dev.cljtoc.ports.randomness :as randomness])
  (:import [java.security SecureRandom]))

;; Fail the compile on reflective calls; the SecureRandom path is hot for
;; peer-id generation.
(set! *warn-on-reflection* true)

(defrecord SecureRandomRandomness []
  randomness/IRandomnessPort
  (random-bytes [_ n]
    (let [^bytes bytes (byte-array n)]
      (.nextBytes (SecureRandom.) bytes)
      bytes)))

(defn create
  "Construct a fresh `SecureRandomRandomness` port."
  []
  (->SecureRandomRandomness))