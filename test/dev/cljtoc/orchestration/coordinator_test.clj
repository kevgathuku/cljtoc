(ns dev.cljtoc.orchestration.coordinator-test
  "Unit tests for the pure coordinator planning kernel (issue #42)."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.orchestration.coordinator :as coordinator]))

(def ^:private coordinator-source
  "Source text of the kernel, read from the working tree (lein runs from root)."
  (slurp "src/dev/cljtoc/orchestration/coordinator.clj"))

(deftest zero-port-requires-test
  (testing "the planning kernel requires no effect ports (pure by construction)"
    (is (some? (read-string (str "(do " coordinator-source ")")))
        "coordinator.clj must be readable")
    (is (not (re-find #"dev\.cljtoc\.ports\." coordinator-source)) "no port requires")
    (is (not (re-find #"core\.async" coordinator-source)) "no core.async")
    (is (not (re-find #"coordination\.peer-worker" coordinator-source)) "no worker wiring")))

(defn- one-piece-state
  []
  (pieces/initial-piece-state 1))

(deftest coordinator-round-trip-test
  (testing "connect then disconnect resolves the dial and requeues nothing"
    (let [download {:piece-state (one-piece-state)}
          state (coordinator/initial-coordinator-state download ["peer-a" "peer-b"])]
      (is (= #{"peer-a" "peer-b"} (:pending-dials state)))
      (let [[connected effects] (coordinator/on-connected state {:address "peer-a"
                                                                 :peer-data :pd
                                                                 :peer-state {}})]
        (is (= [] effects))
        (is (= #{"peer-b"} (:pending-dials connected)))
        (is (false? (coordinator/swarm-exhausted? connected)))
        (let [[dropped effects] (coordinator/on-disconnected connected {:address "peer-a"})]
          (is (= [] effects))
          (is (empty? (:active-peers dropped)))
          ;; peer-b's dial is still in flight, so the swarm is not exhausted.
          (is (false? (coordinator/swarm-exhausted? dropped)))
          ;; A refused dial for peer-b resolves the last pending dial.
          (let [[empty-swarm _] (coordinator/on-disconnected dropped {:address "peer-b"})]
            (is (true? (coordinator/swarm-exhausted? empty-swarm)))))))))

(deftest unknown-peer-message-is-noop-test
  (testing "messages from unknown peers plan no effects"
    (let [state (coordinator/initial-coordinator-state {:piece-state (one-piece-state)} [])]
      (is (= [state []] (coordinator/on-message state {:address "ghost" :message {}} {}))))))
