(ns dev.cljtoc.test-doubles.time
  "Mock time port for testing download orchestration.
   
   Provides controllable time for deterministic testing."
  (:require [dev.cljtoc.ports.time :as time]
            [clojure.core.async :as async]))

(defrecord MockTimePort
           [config
            current-time
            monotonic-time
            time-advances]

  time/ITimePort
  (now [_]
    @current-time)

  (monotonic [_]
    @monotonic-time)

  (set-timeout [_ ms value]
    (let [ch (async/chan 1)]
      (async/go
        (async/<! (async/timeout (get @config :timeout-multiplier 1)))
        (async/>! ch value)
        (async/close! ch))
      ch))

  (set-interval [_ ms value]
    (let [ch (async/chan 1)]
      (async/go-loop []
        (async/>! ch value)
        (async/<! (async/timeout (get @config :interval-multiplier 1)))
        (recur))
      ch)))

(defn create
  "Create a mock time port for testing.
   
   Options:
   - :now - the current time in milliseconds (default: 0)
   - :monotonic - the monotonic time in milliseconds (default: 0)
   - :timeout-multiplier - multiplier for timeout delays (default: 1)
   - :interval-multiplier - multiplier for interval delays (default: 1)"
  ([]
   (create {}))
  ([config]
   (->MockTimePort (atom config)
                   (atom (get config :now 0))
                   (atom (get config :monotonic 0))
                   (atom 0))))

(defn advance-time [mock-time delta-ms]
  (swap! (:current-time mock-time) + delta-ms))

(defn advance-monotonic [mock-time delta-ms]
  (swap! (:monotonic-time mock-time) + delta-ms))
