(ns dev.cljtoc.test-doubles.randomness
  "Mock randomness port for testing.

   Holds a queue of scripted byte sequences in an atom; each call to
   `random-bytes` pulls the head off and returns it as a byte array.
   When the queue is empty, returns a zero-filled byte array of the
   requested length so a test that asks for more than it scripted gets
   deterministic, recognisable bytes (all zero) instead of throwing —
   predictable failures beat mysterious ones."
  (:require [dev.cljtoc.ports.randomness :as randomness]))

(defrecord MockRandomness
           [scripted]
  randomness/IRandomnessPort
  (random-bytes [_ n]
    (let [head (first @scripted)]
      (if head
        (do
          ;; Atom swap so the next call sees the rest of the queue.
          ;; Mock instances are per-test, not shared across threads.
          (swap! scripted rest)
          (if (< (count head) n)
            ;; Short script: the contract says exactly n bytes come back.
            ;; A test that scripts too few bytes has a bug; refusing
            ;; loudly surfaces it rather than silently returning fewer bytes
            ;; (which would let a peer-id test pass with the wrong length).
            (throw (ex-info (str "MockRandomness script under-supplied: asked for "
                                 n " bytes but next entry has " (count head))
                            {:asked n :provided (count head)}))
            (byte-array (take n head))))
        (byte-array n)))))