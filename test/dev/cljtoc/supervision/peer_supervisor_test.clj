(ns dev.cljtoc.supervision.peer-supervisor-test
  "Tests for the one-for-one supervision of a download's peer workers.

   Seam: the worker spawn is injected, so the restart and drop paths
   are driven with no network at all."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [dev.cljtoc.supervision.peer-supervisor :as peer-supervisor]
            [dev.cljtoc.test-utils :as test-utils]))

(def ^:private budget {:limit 3 :window-ms 60000 :now (fn [] 0)})

(defn- wait-for
  "Wait until pred holds, or give up after timeout-ms."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- spawn-count
  "How many workers were spawned for an address."
  [spawns address]
  (count (filter #(= address %) @spawns)))

(defn- take-event
  "The next event on the channel, or :timeout when none arrives in time."
  [events-ch timeout-ms]
  (let [timeout-ch (async/timeout timeout-ms)]
    (async/alt!!
      events-ch ([event] event)
      timeout-ch :timeout)))

(defn- take-drop-event
  "The next event that is not a redial: the drop that ends the address."
  [events-ch]
  (loop [remaining 5]
    (if (zero? remaining)
      nil
      (let [event (take-event events-ch 500)]
        (if (and (not= :timeout event) (not (:redial event)))
          event
          (recur (dec remaining)))))))

(defn- channel-closed?
  "True when the channel closed within timeout-ms."
  [ch timeout-ms]
  (let [timeout-ch (async/timeout timeout-ms)]
    (not= :timeout (async/alt!! ch ([_] :open) timeout-ch :timeout))))

(defn- crashing-spawner
  "A spawn seam that records every spawn. An address in `crashing` gets a
   worker that finishes with a crash outcome; every other address gets a
   worker that never finishes."
  [crashing spawns]
  (fn [_events-ch address]
    (swap! spawns conj address)
    (if (contains? crashing address)
      (let [done (async/chan 1)]
        (async/put! done {:exit :crash :error (ex-info "peer worker crashed" {})})
        done)
      (async/chan 1))))

(defn- clean-spawner
  "A spawn seam whose workers all finish cleanly right away."
  [spawns]
  (fn [_events-ch address]
    (swap! spawns conj address)
    (let [done (async/chan 1)]
      (async/put! done {:exit :clean})
      done)))

(deftest decide-keeps-a-clean-exit-test
  (testing "a worker that exited cleanly is not restarted"
    (let [outcome {:exit :clean}
          decision (peer-supervisor/decide outcome [] budget 0)]
      (is (= :none (:action decision))))))

(deftest decide-restarts-while-the-budget-holds-test
  (testing "a crashed worker is restarted while its budget allows it"
    (let [outcome {:exit :crash}
          decision (peer-supervisor/decide outcome [100 200] budget 1000)]
      (is (= :restart (:action decision)))
      (is (= 2 (:attempt decision)) "the attempt counts the crashes already charged"))))

(deftest decide-drops-when-the-budget-is-spent-test
  (testing "a crashed worker is dropped once its budget ran out"
    (let [outcome {:exit :crash}
          decision (peer-supervisor/decide outcome [100 200 300] budget 1000)]
      (is (= :drop (:action decision))))))

(deftest decide-prunes-the-window-test
  (testing "crashes older than the window no longer count"
    (let [outcome {:exit :crash}
          window {:limit 3 :window-ms 1000 :now (fn [] 0)}
          decision (peer-supervisor/decide outcome [100 200 300] window 5000)]
      (is (= :restart (:action decision))
          "the crashes fell out of the window, so the budget is fresh again"))))

(deftest decide-pins-the-budget-edges-test
  (testing "an empty history always allows a restart"
    (is (= :restart (:action (peer-supervisor/decide {:exit :crash}
                                                     []
                                                     {:limit 1 :window-ms 60000}
                                                     0)))))

  (testing "the tightest budget allows one restart and no more"
    (is (= :restart (:action (peer-supervisor/decide {:exit :crash}
                                                     []
                                                     {:limit 1 :window-ms 60000}
                                                     0))))
    (is (= :drop (:action (peer-supervisor/decide {:exit :crash}
                                                  [100]
                                                  {:limit 1 :window-ms 60000}
                                                  1000)))))

  (testing "a crash older than the window is never charged"
    (is (= :restart (:action (peer-supervisor/decide {:exit :crash}
                                                     [1]
                                                     {:limit 1 :window-ms 1000}
                                                     5000)))))

  (testing "no restart is allowed at all when the limit is zero"
    (is (= :drop (:action (peer-supervisor/decide {:exit :crash}
                                                  []
                                                  {:limit 0 :window-ms 60000}
                                                  0))))))

(deftest decide-specs-hold-generatively-test
  (testing "the generative check holds over the decision's fdef"
    (is (= {} (test-utils/check-fdefs [`peer-supervisor/decide] 100)))))

(deftest supervisor-restarts-a-crashed-worker-alone-test
  (testing "a crashed worker is restarted while the other workers keep running"
    (let [spawns (atom [])
          options {:spawn (crashing-spawner #{"a:1"} spawns)
                   :addresses ["a:1" "b:1"]
                   :budget budget
                   :delay-fn (fn [_] 0)}
          supervisor (peer-supervisor/start options)
          events-ch (:events-ch supervisor)]
      (try
        (is (wait-for #(>= (spawn-count spawns "a:1") 2) 2000)
            "the crashed worker was restarted")
        (is (= 1 (spawn-count spawns "b:1"))
            "the healthy worker was left alone")
        (let [event (take-event events-ch 2000)]
          (is (= :peer-disconnected (:type event)))
          (is (= "a:1" (:address event)))
          (is (:redial event)
              "the address stays dialed: the restart is coming"))
        (finally
          ((:stop supervisor)))))))

(deftest supervisor-dials-the-addresses-it-is-handed-test
  (testing "the supervisor dials the peers the re-announcer found"
    (let [spawns (atom [])
          supervisor (peer-supervisor/start
                      {:spawn (crashing-spawner #{} spawns)
                       :addresses ["a:1"]
                       :budget budget
                       :delay-fn (fn [_] 0)})]
      (try
        (is (wait-for #(= 1 (spawn-count spawns "a:1")) 2000)
            "the first peer was dialed")
        ((:dial supervisor) ["b:1" "c:1"])
        (is (wait-for #(= 3 (count @spawns)) 2000)
            "the peers the trackers named were dialed")
        (is (= 1 (spawn-count spawns "b:1")))
        (is (= 1 (spawn-count spawns "c:1")))
        (finally
          ((:stop supervisor)))))))

(deftest supervisor-skips-an-address-it-could-not-dial-test
  (testing "an address whose worker could not be started is left out"
    (let [spawns (atom [])
          spawn (fn [_events-ch address]
                  (swap! spawns conj address)
                  (if (= address "b:1")
                    (throw (ex-info "the worker could not be started" {}))
                    (async/chan 1)))
          options {:spawn spawn
                   :addresses ["a:1"]
                   :budget budget
                   :delay-fn (fn [_] 0)}
          supervisor (peer-supervisor/start options)]
      (try
        (is (wait-for #(= 1 (spawn-count spawns "a:1")) 2000))
        ((:dial supervisor) ["b:1"])
        (is (wait-for #(= 2 (count @spawns)) 2000)
            "the dial was attempted")
        (Thread/sleep 100)
        (is (= 2 (count @spawns))
            "the address that could not be started was not dialed again")
        (finally
          ((:stop supervisor)))))))

(deftest stopping-the-supervisor-ends-the-workers-test
  (testing "the stop drains the channel, so a worker parked on a full buffer exits"
    (let [exited (atom false)
          options {:spawn (fn [events-ch _address]
                            ;; A worker that reports and keeps reporting,
                            ;; the way the real one does, until the channel
                            ;; closes under it.
                            (let [done (async/chan 1)]
                              (async/thread
                                (loop []
                                  (when (async/>!! events-ch
                                                   {:type :peer-message
                                                    :address "a:1"
                                                    :message {}})
                                    (recur)))
                                (reset! exited true)
                                (async/>!! done {:exit :clean}))
                              done))
                   :addresses ["a:1"]}
          supervisor (peer-supervisor/start options)]
      (try
        ;; Let the worker flood the channel until its put parks.
        (Thread/sleep 200)
        ((:stop supervisor))
        (is (wait-for (fn [] @exited) 2000)
            "the worker exited instead of parking on a full buffer")
        (finally
          ((:stop supervisor)))))))

(deftest supervisor-drops-a-flapping-address-test
  (testing "an address whose budget ran out is dropped instead of restarted"
    (let [spawns (atom [])
          options {:spawn (crashing-spawner #{"a:1"} spawns)
                   :addresses ["a:1"]
                   :budget {:limit 2 :window-ms 60000 :now (fn [] 0)}
                   :delay-fn (fn [_] 0)}
          supervisor (peer-supervisor/start options)
          events-ch (:events-ch supervisor)]
      (try
        (is (wait-for #(= 3 (spawn-count spawns "a:1")) 2000)
            "the initial worker plus the two restarts the budget allowed")
        (let [event (take-drop-event events-ch)]
          (is (= :peer-disconnected (:type event)))
          (is (not (:redial event))
              "a dropped address is not dialed again"))
        (Thread/sleep 100)
        (is (= 3 (spawn-count spawns "a:1"))
            "the dropped address was not restarted again")
        (finally
          ((:stop supervisor)))))))

(deftest supervisor-drops-an-address-whose-restart-fails-test
  (testing "a worker that cannot be restarted is dropped instead of retried forever"
    (let [spawns (atom [])
          spawn (fn [_events-ch address]
                  (let [attempt (count (filter #(= address %) @spawns))]
                    (swap! spawns conj address)
                    (if (zero? attempt)
                      (let [done (async/chan 1)]
                        (async/put! done {:exit :crash :error (ex-info "peer worker crashed" {})})
                        done)
                      (throw (ex-info "the worker could not be started" {})))))
          options {:spawn spawn
                   :addresses ["a:1"]
                   :budget budget
                   :delay-fn (fn [_] 0)}
          supervisor (peer-supervisor/start options)
          events-ch (:events-ch supervisor)]
      (try
        (let [event (take-drop-event events-ch)]
          (is (= :peer-disconnected (:type event)))
          (is (not (:redial event))
              "the address is not dialed again: the restart failed"))
        (finally
          ((:stop supervisor)))))))

(deftest supervisor-reports-a-worker-that-never-started-test
  (testing "a spawn that fails leaves no worker, and the coordinator hears the peer is gone"
    (let [options {:spawn (fn [_events-ch _address]
                            (throw (ex-info "the worker could not be started" {})))
                   :addresses ["a:1"]}
          supervisor (peer-supervisor/start options)
          events-ch (:events-ch supervisor)]
      (try
        (let [event (take-event events-ch 2000)]
          (is (= :peer-disconnected (:type event)))
          (is (= "a:1" (:address event)))
          (is (not (:redial event))
              "the dial resolves: nobody will ever answer for that address"))
        (finally
          ((:stop supervisor)))))))

(deftest supervisor-closes-the-event-channel-when-it-is-stopped-test
  (testing "the supervisor owns the event channel and closes it when it is stopped"
    (let [spawns (atom [])
          options {:spawn (clean-spawner spawns)
                   :addresses ["a:1"]}
          supervisor (peer-supervisor/start options)]
      (is (not (channel-closed? (:events-ch supervisor) 100))
          "the channel stays open while the download may find peers again")
      ((:stop supervisor))
      (is (wait-for #(channel-closed? (:events-ch supervisor) 100) 2000)
          "the channel closed when the supervisor stopped"))))

(deftest supervisor-does-not-restart-a-clean-exit-test
  (testing "a worker that exited cleanly is not restarted"
    (let [spawns (atom [])
          options {:spawn (clean-spawner spawns)
                   :addresses ["a:1"]
                   :delay-fn (fn [_] 0)}
          supervisor (peer-supervisor/start options)]
      (try
        (Thread/sleep 200)
        (is (= 1 (count @spawns))
            "the peer that disconnected cleanly was not dialed again")
        (finally
          ((:stop supervisor)))))))
