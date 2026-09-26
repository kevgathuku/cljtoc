(ns dev.cljtoc.ports.time
  "Time port protocol for time-related effects.
  
   This protocol defines the contract for time operations needed by
   the download orchestration layer. Using a port allows time to be
   mocked in tests for deterministic behavior."
  (:require [clojure.core.async :as async]))

(defprotocol ITimePort
  "Abstraction for time operations needed by download orchestration."

  (now [this]
    "Get current wall-clock time as an instant.
     Returns the current time in milliseconds since epoch.")

  (monotonic [this]
    "Get monotonic time in milliseconds since process start.
     This is useful for measuring elapsed time intervals.")

  (set-timeout [this ms value]
    "Create a channel that will close after the specified milliseconds.
     When the timeout fires, the channel closes and any pending
     value is dropped.
     
     Returns a channel that will deliver value after ms milliseconds.")

  (set-interval [this ms value]
    "Create a channel that will repeatedly deliver value at the
     specified interval.
     
     Returns a channel that will deliver value every ms milliseconds.
     The channel must be closed to stop the interval."))

(defrecord RealTimePort []
  ITimePort
  (now [_]
    (System/currentTimeMillis))

  (monotonic [_]
    (System/nanoTime))

  (set-timeout [_ ms value]
    (let [ch (async/chan 1)]
      (async/go
        (async/<! (async/timeout ms))
        (async/>! ch value)
        (async/close! ch))
      ch))

  (set-interval [_ ms value]
    (let [ch (async/chan 1)]
      (async/go-loop []
        (async/>! ch value)
        (async/<! (async/timeout ms))
        (recur))
      ch)))
