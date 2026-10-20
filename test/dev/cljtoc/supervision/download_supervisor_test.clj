(ns dev.cljtoc.supervision.download-supervisor-test
  "Tests for the supervisor of one download's subtree.

   Seam: the run is injected, so the restart path is driven with no
   network and no disk; the real path runs against the mock ports."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.supervision.download-supervisor :as download-supervisor]
            [dev.cljtoc.test-doubles.disk :as mock-disk]
            [dev.cljtoc.test-doubles.network :as mock-net]
            [dev.cljtoc.test-doubles.time :as mock-time]
            [dev.cljtoc.test-utils :as test-utils]))

(defn- download-record
  "A started download with one piece to fetch."
  []
  (let [piece-0-bytes (test-utils/to-bytes "abcd")
        torrent {:info-hash (bencode/sha1-hash (test-utils/to-bytes "supervised-info"))
                 :info {:pieces [(bencode/sha1-hash piece-0-bytes)]
                        :piece-length 4
                        :name "supervised.bin"
                        :length 4}}]
    (assoc (download/initial-download (mock-time/create) torrent "/out" "supervised")
           :state :downloading
           :peers #{{:address "10.0.0.9:6881"}})))

(deftest supervise-download-runs-the-download-test
  (testing "the real path runs the download to completion"
    (let [piece-0-bytes (test-utils/to-bytes "abcd")
          info-hash (bencode/sha1-hash (test-utils/to-bytes "real-info"))
          torrent {:info-hash info-hash
                   :info {:pieces [(bencode/sha1-hash piece-0-bytes)]
                          :piece-length 4
                          :name "real.bin"
                          :length 4}}
          started (assoc (download/initial-download (mock-time/create) torrent "/out" "real")
                         :state :downloading
                         :peers #{{:address "10.0.0.9:6881"}})
          net (mock-net/create
               {:handshake-response {:ok {:info-hash info-hash
                                          :peer-id (byte-array 20)}}
                :receive-responses (atom [{:ok (peer/->Bitfield
                                                (byte-array [(unchecked-byte 0x80)]))}
                                          {:ok (peer/->Unchoke)}
                                          {:ok (peer/->Piece 0 0 piece-0-bytes)}])})
          disk (mock-disk/create)
          result (download-supervisor/supervise-download
                  (test-utils/download-manager net disk)
                  started
                  {})]
      (is (= :completed (:state result)))
      (is (= 1 (pieces/verified-count (:piece-state result)))))))

(deftest supervise-download-restarts-a-crashed-subtree-test
  (testing "a crashed subtree runs again while the budget holds"
    (let [calls (atom 0)
          record (download-record)
          result (download-supervisor/supervise-download
                  (test-utils/download-manager (mock-net/create) (mock-disk/create))
                  record
                  {:prepare (fn [_manager _record _opts]
                              {:status :ready :plan {:download record}})
                   :run (fn [_manager _plan _publish]
                          (swap! calls inc)
                          (if (= 1 @calls)
                            (throw (ex-info "coordinator crash" {}))
                            {:id "supervised" :state :completed}))})]
      (is (= :completed (:state result)))
      (is (= 2 @calls) "the subtree ran twice: once crashed, once finished"))))

(deftest supervise-download-resumes-from-the-published-record-test
  (testing "a restart resumes from the record the crashed subtree published"
    (let [plans (atom [])
          record (download-record)
          progressed (assoc record
                            :piece-state {:total-pieces 1
                                          :needed #{}
                                          :in-flight #{}
                                          :verified #{0}})
          result (download-supervisor/supervise-download
                  (test-utils/download-manager (mock-net/create) (mock-disk/create))
                  record
                  {:prepare (fn [_manager _record _opts]
                              {:status :ready :plan {:download record}})
                   :run (fn [_manager plan publish]
                          (swap! plans conj (:download plan))
                          (if (= 1 (count @plans))
                            (do (publish progressed)
                                (throw (ex-info "coordinator crash" {})))
                            {:id "supervised" :state :completed}))})]
      (is (= :completed (:state result)))
      (is (= 2 (count @plans)))
      (is (= 0 (count (:verified (:piece-state (first @plans)))))
          "the first attempt ran the prepared record")
      (is (= 1 (count (:verified (:piece-state (second @plans)))))
          "the restart ran the record the crashed attempt published"))))

(deftest supervise-download-fails-when-the-budget-runs-out-test
  (testing "a subtree that keeps crashing fails the download instead of spinning"
    (let [calls (atom 0)
          record (download-record)
          result (download-supervisor/supervise-download
                  (test-utils/download-manager (mock-net/create) (mock-disk/create))
                  record
                  {:budget {:limit 2 :window-ms 60000}
                   :prepare (fn [_manager _record _opts]
                              {:status :ready :plan {:download record}})
                   :run (fn [_manager _plan _publish]
                          (swap! calls inc)
                          (throw (ex-info "coordinator crash" {})))})]
      (is (= :failed (:state result)))
      (is (= :restart-budget-exhausted (get-in result [:error :reason])))
      (is (= 3 @calls) "the initial attempt plus the two restarts the budget allowed"))))

(deftest supervise-download-finishes-without-running-test
  (testing "a download that is already done never runs a subtree"
    (let [record (download-record)
          result (download-supervisor/supervise-download
                  (test-utils/download-manager (mock-net/create) (mock-disk/create))
                  record
                  {:prepare (fn [_manager _record _opts]
                              {:status :finished :record {:id "supervised"
                                                          :state :completed}})
                   :run (fn [& _]
                          (throw (ex-info "the subtree must not run" {})))})]
      (is (= :completed (:state result))))))

(deftest supervise-download-prepares-once-test
  (testing "a restart does not prepare the run again"
    (let [prepares (atom 0)
          runs (atom 0)
          record (download-record)
          result (download-supervisor/supervise-download
                  (test-utils/download-manager (mock-net/create) (mock-disk/create))
                  record
                  {:prepare (fn [_manager _record _opts]
                              (swap! prepares inc)
                              {:status :ready :plan {:download record}})
                   :run (fn [_manager _plan _publish]
                          (swap! runs inc)
                          (if (= 1 @runs)
                            (throw (ex-info "coordinator crash" {}))
                            {:id "supervised" :state :completed}))})]
      (is (= :completed (:state result)))
      (is (= 1 @prepares)
          "preparing again would resolve the output layout against the filesystem twice")
      (is (= 2 @runs)))))

(deftest supervise-download-requeues-the-stranded-pieces-on-a-restart-test
  (testing "a restart asks for the pieces the crashed attempt left in flight"
    (let [plans (atom [])
          record (download-record)
          stranded (assoc record
                          :piece-state {:total-pieces 1
                                        :needed #{}
                                        :in-flight #{0}
                                        :verified #{}})
          result (download-supervisor/supervise-download
                  (test-utils/download-manager (mock-net/create) (mock-disk/create))
                  record
                  {:prepare (fn [_manager _record _opts]
                              {:status :ready :plan {:download record}})
                   :run (fn [_manager plan publish]
                          (swap! plans conj (:download plan))
                          (if (= 1 (count @plans))
                            (do (publish stranded)
                                (throw (ex-info "coordinator crash" {})))
                            {:id "supervised" :state :completed}))})]
      (is (= :completed (:state result)))
      (is (empty? (:in-flight (:piece-state (first @plans))))
          "the first attempt ran the prepared record")
      (is (= #{0} (:needed (:piece-state (second @plans))))
          "the restart asks for the piece the crashed attempt left in flight")
      (is (empty? (:in-flight (:piece-state (second @plans))))
          "nothing is left in flight, so the restarted swarm can ask for it"))))

(deftest supervise-download-keeps-the-verified-pieces-when-the-budget-runs-out-test
  (testing "a download that crashed past its budget keeps the pieces it verified"
    (let [record (download-record)
          progressed (assoc record
                            :piece-state {:total-pieces 1
                                          :needed #{}
                                          :in-flight #{}
                                          :verified #{0}})
          result (download-supervisor/supervise-download
                  (test-utils/download-manager (mock-net/create) (mock-disk/create))
                  record
                  {:budget {:limit 1 :window-ms 60000}
                   :prepare (fn [_manager _record _opts]
                              {:status :ready :plan {:download record}})
                   :run (fn [_manager _plan publish]
                          (publish progressed)
                          (throw (ex-info "coordinator crash" {})))})]
      (is (= :failed (:state result)))
      (is (= :restart-budget-exhausted (get-in result [:error :reason])))
      (is (= 1 (pieces/verified-count (:piece-state result)))
          "the verified piece survives the supervisor's give-up"))))
