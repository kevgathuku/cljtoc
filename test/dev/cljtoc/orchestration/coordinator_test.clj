(ns dev.cljtoc.orchestration.coordinator-test
  "Unit tests for the pure coordinator planning kernel (issue #42)."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]
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
          state (coordinator/initial-state download ["peer-a" "peer-b"])]
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

(defn- ready-peer-state
  "Peer state that may be given a piece: unchoked, interested, idle."
  []
  (-> (peer-state/initial-peer-state 2)
      (peer-state/set-am-interested true)
      (peer-state/apply-message (peer/->Unchoke))
      (peer-state/mark-piece-available 0)
      (peer-state/mark-piece-available 1)))

(defn- message-state []
  {:download {:piece-state (pieces/initial-piece-state 2)}
   :active-peers {"peer-a" {:peer-data {:id "data-a"}
                            :peer-state (ready-peer-state)
                            :assigned-piece nil}}
   :blocks-received {}
   :expected-blocks {}})

(deftest choke-while-idle-keeps-state-test
  (testing "choke with no assigned piece plans nothing and requeues nothing"
    (let [[updated effects] (coordinator/on-message
                             (message-state)
                             {:address "peer-a" :message (peer/->Choke)}
                             {:piece-length 4 :total-length 8})]
      (is (= [] effects))
      ;; Nothing requeued: both pieces still needed, none in flight,
      ;; assignment untouched. (The peer-state itself flips to choking —
      ;; the message is applied before planning.)
      (is (nil? (get-in updated [:active-peers "peer-a" :assigned-piece])))
      (is (= #{0 1} (get-in updated [:download :piece-state :needed])))
      (is (empty? (get-in updated [:download :piece-state :in-flight]))))))

(deftest stray-piece-for-unassigned-index-ignored-test
  (testing "a Piece for an index the peer was not assigned is ignored, peer may request"
    (let [state (assoc-in (message-state) [:active-peers "peer-a" :assigned-piece] 0)
          stray (peer/->Piece 1 0 (byte-array 0))
          result (coordinator/on-message
                  state
                  {:address "peer-a" :message stray}
                  {:piece-length 4 :total-length 8})]
      ;; Ignored outright: the peer keeps piece 0 and plans nothing, since
      ;; it is still busy (not requestable) no follow-up request is planned.
      (is (= [state []] result)))))

(deftest unknown-peer-message-is-noop-test
  (testing "messages from unknown peers plan no effects"
    (let [state (coordinator/initial-state {:piece-state (one-piece-state)} [])]
      (is (= [state []] (coordinator/on-message state {:address "ghost" :message {}} {}))))))
