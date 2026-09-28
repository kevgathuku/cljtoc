(ns dev.cljtoc.orchestration.download-test
  "Unit tests for download orchestration."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]
            [dev.cljtoc.test-utils :as test-utils]
            [dev.cljtoc.test-doubles.network :as mock-net]
            [dev.cljtoc.test-doubles.disk :as mock-disk]
            [dev.cljtoc.test-doubles.time :as mock-time])
  (:import [java.util UUID]))

(deftest initial-stats-test
  (let [stats (download/initial-stats (mock-time/create))]
    (is (some? (:started-at stats)))
    (is (nil? (:completed-at stats)))
    (is (= 0 (:bytes-downloaded stats)))
    (is (= 0 (:bytes-uploaded stats)))))

(deftest initial-download-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 3))
                 :length 786432
                 :files []}
        d (download/initial-download (mock-time/create) torrent "/output" "test")]
    (is (some? (:id d)))
    (is (= torrent (:torrent d)))
    (is (= :starting (:state d)))
    (is (= "/output" (:output-dir d)))
    (is (some? (:piece-state d)))
    (is (empty? (:peers d)))))

(deftest progress-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 10))
                 :length 2621440
                 :files []}
        piece-state (:ok (pieces/mark-in-flight (pieces/initial-piece-state 10) 0))
        piece-state (:ok (pieces/mark-in-flight piece-state 1))
        piece-state (:ok (pieces/mark-verified piece-state 0))
        piece-state (:ok (pieces/mark-verified piece-state 1))
        now (System/currentTimeMillis)
        stats (download/->DownloadStats now nil 524288 0 now)
        d {:id (UUID/randomUUID)
           :torrent torrent
           :piece-state piece-state
           :peers #{}
           :state :downloading
           :output-dir "/output"
           :stats stats
           :error nil}
        prog (download/progress (mock-time/create) d)]
    (is (= 20.0 (:percent prog)))
    (is (= 2 (:pieces-complete prog)))
    (is (= 10 (:pieces-total prog)))
    (is (= 524288 (:bytes-downloaded prog)))
    (is (= 0 (:peers-connected prog)))
    (is (= :downloading (:state prog)))
    (is (number? (:rate-bytes-per-sec prog)))))

(deftest progress-rate-calculation-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 10))
                 :length 2621440
                 :files []}
        piece-state (pieces/initial-piece-state 10)
        now (System/currentTimeMillis)
        one-second-ago (- now 1000)
        stats (download/->DownloadStats one-second-ago nil 16384 0 one-second-ago)
        d {:id (UUID/randomUUID)
           :torrent torrent
           :piece-state piece-state
           :peers #{{:id "peer1" :address "127.0.0.1" :port 6881}}
           :state :downloading
           :output-dir "/output"
           :stats stats
           :error nil}
        prog (download/progress (mock-time/create {:now (System/currentTimeMillis)}) d)]
    (is (= 16384 (:bytes-downloaded prog)))
    (is (= 1 (:peers-connected prog)))
    (is (> (:rate-bytes-per-sec prog) 0))))

;; Deterministic rates (issue #5): stats fns read time through the
;; ITimePort seam, so MockTimePort + advance-time pin exact rates.

(deftest calculate-rate-uses-time-port-test
  (let [time (mock-time/create {:now 2000})
        stats (download/->DownloadStats 1000 nil 16384 0 1000)]
    (is (= 16384 (download/calculate-rate time stats)))))

(deftest advance-time-drives-rate-test
  (let [time (mock-time/create {:now 1000})
        stats (download/->DownloadStats 1000 nil 0 0 1000)]
    (mock-time/advance-time time 1000)
    (let [updated (download/update-stats-bytes time stats 16384)]
      (is (= 16384 (:bytes-downloaded updated)))
      (is (= 16384 (:rate updated)))
      (is (= 2000 (:last-update updated))))))

(deftest update-stats-bytes-test
  (let [now (System/currentTimeMillis)
        stats (download/->DownloadStats now nil 1000 0 now)
        updated (download/update-stats-bytes (mock-time/create {:now now}) stats 500)]
    (is (= 1500 (:bytes-downloaded updated)))
    (is (>= (:last-update updated) now))))

(deftest stop-download-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 10))
                 :length 2621440
                 :files []}
        piece-state (pieces/initial-piece-state 10)
        stats (download/->DownloadStats (System/currentTimeMillis) nil 524288 0 (System/currentTimeMillis))
        d {:id (UUID/randomUUID)
           :torrent torrent
           :piece-state piece-state
           :peers #{{:id "peer1"}}
           :state :downloading
           :output-dir "/output"
           :stats stats
           :error nil}
        stopped (download/stop-download d)]
    (is (= :idle (:state stopped)))
    (is (empty? (:peers stopped)))))

;; Error Handling Tests

(deftest requeue-piece-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 10))
                 :length 2621440
                 :files []}
        piece-state (:ok (pieces/mark-in-flight (pieces/initial-piece-state 10) 5))
        d {:id (UUID/randomUUID)
           :torrent torrent
           :piece-state piece-state
           :peers #{}
           :state :downloading
           :output-dir "/output"
           :stats (download/initial-stats (mock-time/create))
           :error nil}
        requeued (download/requeue-piece d 5)]
    (is (contains? (get-in requeued [:piece-state :needed]) 5))
    (is (not (contains? (get-in requeued [:piece-state :in-flight]) 5)))))

(deftest handle-peer-disconnect-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 10))
                 :length 2621440
                 :files []}
        piece-state (:ok (pieces/mark-in-flight (pieces/initial-piece-state 10) 3))
        peer {:id "peer1" :address "127.0.0.1" :port 6881}
        d {:id (UUID/randomUUID)
           :torrent torrent
           :piece-state piece-state
           :peers #{peer}
           :state :downloading
           :output-dir "/output"
           :stats (download/initial-stats (mock-time/create))
           :error nil}
        updated (download/handle-peer-disconnect d "peer1")]
    (is (empty? (:peers updated)))
    (is (contains? (get-in updated [:piece-state :needed]) 3))))

(deftest transition-to-failed-test
  (let [d {:state :downloading}
        error-info {:reason :no-peers :message "No peers available" :failed-piece nil}
        failed (download/transition-to-failed d error-info)]
    (is (= :failed (:state failed)))
    (is (= error-info (:error failed)))))

(deftest can-retry-test
  (let [d-no-error {:error nil}
        d-under-limit {:error {:retry-count 2}}
        d-at-limit {:error {:retry-count 3}}]
    (is (true? (download/can-retry? d-no-error)))
    (is (true? (download/can-retry? d-under-limit)))
    (is (false? (download/can-retry? d-at-limit)))))

(deftest retry-download-test
  (let [failed-d {:state :failed
                  :error {:reason :no-peers :retry-count 2}}
        retried (download/retry-download failed-d)]
    (is (= :starting (:state retried)))
    (is (= 3 (get-in retried [:error :retry-count])))))

(deftest retry-download-max-retries-test
  (let [failed-d {:state :failed
                  :error {:reason :no-peers :retry-count 3}}
        result (download/retry-download failed-d)]
    (is (= :max-retries-exceeded (:error result)))))

(deftest handle-no-peers-test
  (let [piece-state (pieces/initial-piece-state 10)
        incomplete-d {:state :downloading
                      :piece-state piece-state
                      :peers #{}
                      :error nil}
        result (download/handle-no-peers incomplete-d)]
    (is (= :failed (:state result)))
    (is (= :no-peers (get-in result [:error :reason])))))

(deftest handle-no-peers-when-complete-test
  (let [ps (pieces/initial-piece-state 10)
        ps (reduce (fn [ps i] (:ok (pieces/mark-in-flight ps i))) ps (range 10))
        ps (reduce (fn [ps i] (:ok (pieces/mark-verified ps i))) ps (range 10))
        complete-d {:state :downloading
                    :piece-state ps
                    :peers #{}
                    :error nil}
        result (download/handle-no-peers complete-d)]
    (is (= :completed (:state result)))))

;; Pause/Resume Tests (User Story 4)

(deftest pause-download-basic-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 10))
                 :length 2621440
                 :files []}
        piece-state (pieces/initial-piece-state 10)
        peer {:id "peer1" :address "127.0.0.1" :port 6881}
        d {:id (UUID/randomUUID)
           :torrent torrent
           :piece-state piece-state
           :peers #{peer}
           :state :downloading
           :output-dir "/output"
           :stats (download/initial-stats (mock-time/create))
           :error nil}
        result (download/pause-download d)]
    (is (= :paused (get-in result [:ok :state])))
    (is (empty? (:peers (result :ok))))))

(deftest pause-download-not-running-test
  (let [d {:state :idle}]
    (is (= :not-running (:error (download/pause-download d))))))

(deftest resume-download-basic-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 10))
                 :length 2621440
                 :files []}
        piece-state (pieces/initial-piece-state 10)
        d {:id (UUID/randomUUID)
           :torrent torrent
           :piece-state piece-state
           :peers #{}
           :state :paused
           :output-dir "/output"
           :stats (download/initial-stats (mock-time/create))
           :error nil}
        result (download/resume-download d)]
    (is (= :downloading (get-in result [:ok :state])))))

(deftest resume-download-not-paused-test
  (let [d {:state :downloading}]
    (is (= :not-paused (:error (download/resume-download d))))))

(deftest pause-download-with-disk-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 10))
                 :length 2621440
                 :files []}
        piece-state (pieces/initial-piece-state 10)
        peer {:id "peer1" :address "127.0.0.1" :port 6881}
        d {:id (UUID/randomUUID)
           :torrent torrent
           :piece-state piece-state
           :peers #{peer}
           :state :downloading
           :output-dir "/output"
           :stats (download/initial-stats (mock-time/create))
           :error nil}
        disk (mock-disk/create)
        result (download/pause-download disk d)]
    (is (= :paused (get-in result [:ok :state])))
    (is (empty? (get-in result [:ok :peers])))))

;; start-download builds peers through the peer-address seam

(deftest start-download-builds-peers-with-host-and-port
  (testing "announced peers become records with canonical address, host and port"
    (let [disk (mock-disk/create)
          _ (mock-disk/add-torrent disk "/t.torrent"
                                   {:info-hash (byte-array 20)
                                    :info {:pieces ["h1" "h2"]}})
          net (mock-net/create {:mock-peers ["10.0.0.1:6881" "10.0.0.2"]})
          result (download/start-download {:network-port net :disk-port disk :time-port (mock-time/create)}
                                          "/t.torrent" "/out")]
      (is (= :downloading (:state result)))
      (is (= #{["10.0.0.1:6881" 6881] ["10.0.0.2:6881" 6881]}
             (set (map (juxt :address :port) (:peers result)))))))
  (testing "peers with garbage ports are skipped, not fatal"
    (let [disk (mock-disk/create)
          _ (mock-disk/add-torrent disk "/t.torrent"
                                   {:info-hash (byte-array 20)
                                    :info {:pieces ["h1" "h2"]}})
          net (mock-net/create {:mock-peers ["10.0.0.1:6881" "bad:port"]})
          result (download/start-download {:network-port net :disk-port disk :time-port (mock-time/create)}
                                          "/t.torrent" "/out")]
      (is (= :downloading (:state result)))
      (is (= #{["10.0.0.1:6881" 6881]}
             (set (map (juxt :address :port) (:peers result)))))))
  (testing "bracketed IPv6 peers keep their announced port"
    (let [disk (mock-disk/create)
          _ (mock-disk/add-torrent disk "/t.torrent"
                                   {:info-hash (byte-array 20)
                                    :info {:pieces ["h1" "h2"]}})
          net (mock-net/create {:mock-peers ["[::1]:51413"]})
          result (download/start-download {:network-port net :disk-port disk :time-port (mock-time/create)}
                                          "/t.torrent" "/out")]
      (is (= :downloading (:state result)))
      (is (= #{["[::1]:51413" 51413]}
             (set (map (juxt :address :port) (:peers result))))))))

;; (peer-address construction is covered by start-download-builds-peers-with-host-and-port
;;  above and the dev.cljtoc.domain.peer-address-test suite.)

;; max-peers enforcement (issue #5): the download never holds more peers
;; than the configured limit, so run-download can never dial past it.

(deftest start-download-caps-peers-at-max-peers-test
  (let [disk (mock-disk/create)
        _ (mock-disk/add-torrent disk "/t.torrent"
                                 {:info-hash (byte-array 20)
                                  :info {:pieces ["h1" "h2"]}})
        net (mock-net/create {:mock-peers ["10.0.0.1:6881" "10.0.0.2:6881"
                                           "10.0.0.3:6881" "10.0.0.4:6881"
                                           "10.0.0.5:6881"]})
        time (mock-time/create)
        result (download/start-download {:network-port net
                                         :disk-port disk
                                         :time-port time
                                         :config {:max-peers 2}}
                                        "/t.torrent" "/out")]
    (is (= :downloading (:state result)))
    (is (= 2 (count (:peers result))))))

;; DownloadManager constructor (issue #5): merges default-config,
;; validates the result against ::config.

(deftest run-download-fails-fast-with-no-peers-test
  (testing "zero peers returns :failed instead of blocking on the event channel"
    (let [torrent {:info-hash (byte-array 20)
                   :info {:pieces ["h1" "h2"] :piece-length 262144 :length 524288 :name "no-peers.bin"}}
          started (assoc (download/initial-download (mock-time/create) torrent "/out" "no-peers")
                         :state :downloading
                         :peers #{})
          result (download/run-download {:network-port (mock-net/create)
                                         :disk-port (mock-disk/create)
                                         :time-port (mock-time/create)
                                         :config {}}
                                        started)]
      (is (= :failed (:state result)))
      (is (= :no-peers (get-in result [:error :reason]))))))

(deftest run-download-guard-reads-capped-addresses-test
  (testing "a cap that dials nothing fails fast with the no-peers guard (issue #32)"
    (let [torrent {:info-hash (byte-array 20)
                   :info {:pieces ["h1" "h2"] :piece-length 262144 :length 524288 :name "capped.bin"}}
          started (assoc (download/initial-download (mock-time/create) torrent "/out" "capped-empty")
                         :state :downloading
                         :peers #{{:address "10.0.0.1:6881"}})
          result (download/run-download {:network-port (mock-net/create)
                                         :disk-port (mock-disk/create)
                                         :time-port (mock-time/create)
                                         :config {:max-peers 0}}
                                        started)]
      (is (= :failed (:state result)))
      (is (= :no-peers (get-in result [:error :reason])))
      (is (= "No peers available: nothing to connect to" (get-in result [:error :message]))))))

(deftest manager-rejects-invalid-config-test
  (let [network (mock-net/create)
        disk (mock-disk/create)
        time (mock-time/create)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (download/manager network disk time {:max-peers -1})))))

(deftest manager-rejects-zero-max-peers-test
  (testing "zero max-peers would dial no workers and hang run-download"
    (let [network (mock-net/create)
          disk (mock-disk/create)
          time (mock-time/create)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (download/manager network disk time {:max-peers 0}))))))

(deftest manager-merges-default-config-test
  (let [network (mock-net/create)
        disk (mock-disk/create)
        time (mock-time/create)
        m (download/manager network disk time {})]
    (is (= 50 (get-in m [:config :max-peers])))))

;; Resume must rediscover peers (PR #9 discussion r4111802928): pause clears
;; :peers before saving, and run-download derives every worker from
;; (:peers download) — resuming with zero workers hangs on its event channel.

(deftest resume-download-rediscovers-peers-test
  (let [torrent {:info-hash (byte-array 20)
                 :info {:pieces ["h1" "h2"]}}
        paused (assoc (download/initial-download (mock-time/create) torrent "/output" "resume-me")
                      :state :paused
                      :peers #{})
        net (mock-net/create {:mock-peers ["10.9.9.1:6881" "10.9.9.2:6881"]})
        result (download/resume-download nil net paused)]
    (is (= :downloading (get-in result [:ok :state])))
    (is (= #{["10.9.9.1:6881" 6881] ["10.9.9.2:6881" 6881]}
           (set (map (juxt :address :port) (get-in result [:ok :peers])))))))

(deftest resume-download-tracker-failure-test
  (let [torrent {:info-hash (byte-array 20)
                 :info {:pieces ["h1" "h2"]}}
        paused (assoc (download/initial-download (mock-time/create) torrent "/output" "resume-me")
                      :state :paused
                      :peers #{})
        net (mock-net/create {:announce-error {:error :tracker-error
                                               :message "tracker down"}})
        result (download/resume-download nil net paused)]
    (is (= :tracker-error (:error result)))))

(defn- progress-fixture
  "A 2-piece single-file download (pieces of 4+4, total 8) with piece 0
   verified. Returns {:download :torrent}."
  []
  (let [torrent {:info-hash (byte-array 20)
                 :info {:pieces ["h1" "h2"] :piece-length 4 :length 8 :name "p.bin"}}
        piece-state (-> (pieces/initial-piece-state 2)
                        (#(:ok (pieces/mark-in-flight % 0)))
                        (#(:ok (pieces/mark-verified % 0))))]
    {:torrent torrent
     :download (assoc (download/initial-download (mock-time/create) torrent "/out" "prog")
                      :piece-state piece-state)}))

(deftest announce-progress-reports-verified-bytes-test
  (testing "a fresh download announces nothing downloaded, all bytes left"
    (let [torrent {:info-hash (byte-array 20)
                   :info {:pieces ["h1" "h2"] :piece-length 4 :length 8 :name "p.bin"}}
          download (download/initial-download (mock-time/create) torrent "/out" "fresh")]
      (is (= {:downloaded 0 :left 8} (#'download/announce-progress download)))))
  (testing "a verified piece counts toward downloaded"
    (let [{:keys [download]} (progress-fixture)]
      (is (= {:downloaded 4 :left 4} (#'download/announce-progress download)))))
  (testing "the short tail piece counts at its real length, not a full piece"
    (let [torrent {:info-hash (byte-array 20)
                   :info {:pieces ["h1" "h2"] :piece-length 4 :length 6 :name "p.bin"}}
          piece-state (-> (pieces/initial-piece-state 2)
                          (#(:ok (pieces/mark-in-flight % 1)))
                          (#(:ok (pieces/mark-verified % 1))))
          download (assoc (download/initial-download (mock-time/create) torrent "/out" "tail")
                          :piece-state piece-state)]
      (is (= {:downloaded 2 :left 4} (#'download/announce-progress download)))))
  (testing "records without size metadata announce zero without throwing"
    (let [torrent {:info-hash (byte-array 20)
                   :info {:pieces ["h1" "h2"]}}
          download (download/initial-download (mock-time/create) torrent "/out" "bare")]
      (is (= {:downloaded 0 :left 0} (#'download/announce-progress download))))))

(deftest resume-download-announces-verified-progress-test
  (testing "the tracker re-announce on resume reports verified bytes as
            downloaded instead of zero, so the tracker stops counting a
            resumed leecher as empty"
    (let [{:keys [download]} (progress-fixture)
          paused (assoc download
                        :state :failed
                        :peers #{}
                        :error {:reason :no-peers :message "gone"})
          captured (atom nil)
          net (mock-net/create {:mock-peers ["10.9.9.1:6881"]
                                :announce-capture captured})
          result (download/resume-download nil net paused)]
      (is (= :downloading (get-in result [:ok :state])))
      (is (= {:downloaded 4 :left 4} (:progress @captured))))))

(defn- complete-resume-fixture
  "A 2-piece single-file download with both pieces verified and both bytes
    in the piece cache: a resume with nothing left to fetch.
    Returns {:download :disk}."
  []
  (let [piece-0 (test-utils/to-bytes "abcd")
        piece-1 (test-utils/to-bytes "efgh")
        info {:pieces [(bencode/sha1-hash piece-0)
                       (bencode/sha1-hash piece-1)]
              :piece-length 4
              :name "done.bin"
              :length 8}
        torrent {:info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
                 :info info}
        piece-state (-> (pieces/initial-piece-state 2)
                        (#(:ok (pieces/mark-in-flight % 0)))
                        (#(:ok (pieces/mark-verified % 0)))
                        (#(:ok (pieces/mark-in-flight % 1)))
                        (#(:ok (pieces/mark-verified % 1))))
        download (assoc (download/initial-download (mock-time/create)
                                                   torrent
                                                   "/out"
                                                   "done")
                        :state :paused
                        :peers #{}
                        :piece-state piece-state)
        disk (mock-disk/create)]
    (disk/write-piece disk 0 piece-0)
    (disk/write-piece disk 1 piece-1)
    {:download download :disk disk}))

(deftest resume-download-completes-from-cache-without-announcing-test
  (testing "an all-verified resume reconciles the piece cache before the
            tracker announce: with every cached piece valid the download is
            already complete, so no announce is attempted even when the
            tracker is down"
    (let [{:keys [download disk]} (complete-resume-fixture)
          captured (atom nil)
          net (mock-net/create {:announce-error {:error :tracker-error
                                                 :message "tracker down"}
                                :announce-capture captured})
          result (download/resume-download disk net download)]
      (is (nil? @captured) "no tracker announce was attempted")
      (is (= :completed (get-in result [:ok :state])))
      (is (pieces/complete? (get-in result [:ok :piece-state]))))))

(deftest resume-download-announces-reconciled-progress-test
  (testing "a verified piece the cache no longer holds is requeued before
            the announce, so the tracker hears the corrected progress
            instead of the stale verified set"
    (let [{:keys [download]} (complete-resume-fixture)
          ;; Fresh port: the record still claims both pieces verified, but
          ;; the cache holds neither.
          disk (mock-disk/create)
          captured (atom nil)
          net (mock-net/create {:mock-peers ["10.9.9.1:6881"]
                                :announce-capture captured})
          result (download/resume-download disk net download)]
      (is (= :downloading (get-in result [:ok :state])))
      (is (= {:downloaded 0 :left 8} (:progress @captured)))
      (is (= #{0 1} (:needed (get-in result [:ok :piece-state])))))))

;; Canonical download IDs (issue #7): human-readable, derived once from the
;; torrent path — never a UUID, never overwritten post-hoc.

(deftest initial-download-uses-given-id-test
  (let [torrent {:info-hash (byte-array 20)
                 :name "test.torrent"
                 :piece-length 262144
                 :pieces (byte-array (* 20 3))
                 :length 786432
                 :files []}
        d (download/initial-download (mock-time/create) torrent "/output" "my-torrent")]
    (is (= "my-torrent" (:id d)))
    (is (string? (:id d)))))

(deftest start-download-derives-id-from-path-test
  (let [disk (mock-disk/create)
        _ (mock-disk/add-torrent disk "/dl/my-torrent.torrent"
                                 {:info-hash (byte-array 20)
                                  :info {:pieces ["h1" "h2"]}})
        net (mock-net/create {:mock-peers ["10.0.0.1:6881"]})
        result (download/start-download {:network-port net :disk-port disk :time-port (mock-time/create)}
                                        "/dl/my-torrent.torrent" "/out")]
    (is (= :downloading (:state result)))
    (is (= "my-torrent" (:id result)))))

;; Coordinator state helpers (issue #2): the run-download loop threads one
;; state map {:download :active-peers :blocks-received :expected-blocks}.

(defn- coordinator-state [piece-index]
  (let [piece-state (:ok (pieces/mark-in-flight (pieces/initial-piece-state 2) piece-index))]
    {:download {:piece-state piece-state}
     :active-peers {"peer-a" {:assigned-piece piece-index}}
     :blocks-received {"peer-a" [{:offset 0}]}
     :expected-blocks {"peer-a" 1}}))

(deftest requeue-assignment-test
  (testing "requeues the piece, clears the assignment and drops buffered blocks"
    (let [updated (download/requeue-assignment (coordinator-state 1) "peer-a" 1)]
      (is (contains? (get-in updated [:download :piece-state :needed]) 1))
      (is (nil? (get-in updated [:active-peers "peer-a" :assigned-piece])))
      (is (not (contains? (:blocks-received updated) "peer-a")))))
  (testing "still clears bookkeeping when the piece is no longer in-flight"
    (let [state (assoc-in (coordinator-state 1) [:download :piece-state :in-flight] #{})
          updated (download/requeue-assignment state "peer-a" 1)]
      (is (nil? (get-in updated [:active-peers "peer-a" :assigned-piece])))
      (is (not (contains? (:blocks-received updated) "peer-a"))))))

(deftest on-connected-test
  (testing "records the peer with no assignment and no effects"
    (let [peer-state-value (peer-state/initial-peer-state 2)
          state {:download {} :active-peers {} :blocks-received {} :expected-blocks {}}
          [updated effects] (download/on-connected state {:address "peer-a"
                                                          :peer-data {:id "data-a"}
                                                          :peer-state peer-state-value})]
      (is (= [] effects))
      (is (= {:peer-data {:id "data-a"}
              :peer-state peer-state-value
              :assigned-piece nil}
             (get-in updated [:active-peers "peer-a"])))))
  (testing "a resolving dial leaves the pending set"
    (let [peer-state-value (peer-state/initial-peer-state 2)
          state (download/initial-coordinator-state {} ["peer-a" "peer-b"])
          [updated effects] (download/on-connected state {:address "peer-a"
                                                          :peer-data {:id "data-a"}
                                                          :peer-state peer-state-value})]
      (is (= [] effects))
      (is (= #{"peer-b"} (:pending-dials updated))))))

(deftest on-disconnected-test
  (testing "requeues the assigned piece and drops the peer with no effects"
    (let [state (assoc (coordinator-state 1)
                       :active-peers {"peer-a" {:assigned-piece 1}
                                      "peer-b" {:assigned-piece nil}})
          [updated effects] (download/on-disconnected state {:address "peer-a"
                                                             :reason "boom"})]
      (is (= [] effects))
      (is (contains? (get-in updated [:download :piece-state :needed]) 1))
      (is (not (contains? (:active-peers updated) "peer-a")))
      (is (not (contains? (:blocks-received updated) "peer-a")))
      (is (not (contains? (:expected-blocks updated) "peer-a")))))
  (testing "dropping the last peer leaves an exhausted swarm"
    (let [[updated effects] (download/on-disconnected (coordinator-state 1)
                                                      {:address "peer-a"
                                                       :reason "boom"})]
      (is (= [] effects))
      (is (empty? (:active-peers updated)))
      (is (true? (download/swarm-exhausted? updated)))))
  (testing "a complete download is never exhausted"
    (let [complete-state (pieces/initial-piece-state 2)
          complete-state (:ok (pieces/mark-in-flight complete-state 0))
          complete-state (:ok (pieces/mark-in-flight complete-state 1))
          complete-state (:ok (pieces/mark-verified complete-state 0))
          complete-state (:ok (pieces/mark-verified complete-state 1))
          state {:download {:piece-state complete-state}
                 :active-peers {"peer-a" {:assigned-piece nil}}
                 :blocks-received {}
                 :expected-blocks {}}
          [updated effects] (download/on-disconnected state {:address "peer-a"
                                                             :reason "bye"})]
      (is (= [] effects))
      (is (empty? (:active-peers updated)))
      (is (false? (download/swarm-exhausted? updated)))))
  (testing "unknown address drops nothing and the swarm lives on"
    (let [state (assoc (coordinator-state 1)
                       :active-peers {"peer-a" {:assigned-piece 1}})
          [updated effects] (download/on-disconnected state {:address "ghost"
                                                             :reason "boom"})]
      (is (= [] effects))
      (is (false? (download/swarm-exhausted? updated)))
      (is (contains? (:active-peers updated) "peer-a"))
      (is (contains? (get-in updated [:download :piece-state :in-flight]) 1))))
  (testing "a refused dial leaves the pending set without touching peers"
    (let [state (assoc (coordinator-state 1)
                       :active-peers {}
                       :pending-dials #{"peer-a" "peer-b"})
          [updated effects] (download/on-disconnected state {:address "peer-a"
                                                             :reason "refused"})]
      (is (= [] effects))
      (is (= #{"peer-b"} (:pending-dials updated)))
      (is (false? (download/swarm-exhausted? updated)))))
  (testing "dropping a connected peer leaves pending dials alone"
    (let [state (assoc (coordinator-state 1)
                       :active-peers {"peer-a" {:assigned-piece nil}}
                       :pending-dials #{"peer-b"})
          [updated effects] (download/on-disconnected state {:address "peer-a"
                                                             :reason "boom"})]
      (is (= [] effects))
      (is (= #{"peer-b"} (:pending-dials updated)))
      (is (false? (download/swarm-exhausted? updated)))))
  (testing "a duplicate disconnect for a resolved dial is a no-op on pending"
    (let [state (assoc (coordinator-state 1)
                       :active-peers {}
                       :pending-dials #{})
          [updated effects] (download/on-disconnected state {:address "peer-a"
                                                             :reason "late duplicate"})]
      (is (= [] effects))
      (is (= #{} (:pending-dials updated)))
      (is (true? (download/swarm-exhausted? updated))))))

;; swarm-exhausted? (issue #11): pending dials count as a live swarm —
;; failure only when nothing is active, nothing is dialing, and pieces
;; are still incomplete.

(deftest swarm-exhausted-pending-dials-test
  (testing "dials in flight mean the swarm is not exhausted"
    (let [state (assoc (coordinator-state 1)
                       :active-peers {}
                       :pending-dials #{"peer-b"})]
      (is (false? (download/swarm-exhausted? state)))))
  (testing "no active peers and no pending dials is exhausted"
    (let [state (assoc (coordinator-state 1)
                       :active-peers {}
                       :pending-dials #{})]
      (is (true? (download/swarm-exhausted? state)))))
  (testing "active peers still count, pending or not"
    (let [state (assoc (coordinator-state 1)
                       :pending-dials #{"peer-b"})]
      (is (false? (download/swarm-exhausted? state)))))
  (testing "a complete download is never exhausted"
    (let [complete-state (pieces/initial-piece-state 2)
          complete-state (:ok (pieces/mark-in-flight complete-state 0))
          complete-state (:ok (pieces/mark-in-flight complete-state 1))
          complete-state (:ok (pieces/mark-verified complete-state 0))
          complete-state (:ok (pieces/mark-verified complete-state 1))
          state {:download {:piece-state complete-state}
                 :active-peers {}
                 :pending-dials #{}
                 :blocks-received {}
                 :expected-blocks {}}]
      (is (false? (download/swarm-exhausted? state))))))

;; on-message takes [state event ctx] and returns [new-state effects].
;; Effects are data: {:send {:peer-data ... :bytes ...}} for block requests,
;; {:write-verified {:piece-idx ... :data ...}} for verified pieces.
;; Channel I/O stays at the run-download loop edge.

(defn- ready-peer-state []
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

(defn- message-ctx [hashes]
  {:piece-hashes hashes :piece-length 4 :total-length 8 :total-pieces 2})

(defn- two-piece-hashes []
  [(bencode/sha1-hash (test-utils/to-bytes "abcd"))
   (bencode/sha1-hash (test-utils/to-bytes "efgh"))])

(deftest on-message-unchoke-plans-request-test
  (testing "unchoke assigns the rarest-needed piece and plans its block sends"
    (let [[updated effects] (download/on-message
                             (message-state)
                             {:address "peer-a" :message (peer/->Unchoke)}
                             (message-ctx (two-piece-hashes)))]
      (is (= 0 (get-in updated [:active-peers "peer-a" :assigned-piece])))
      (is (contains? (get-in updated [:download :piece-state :in-flight]) 0))
      (is (= {"peer-a" 1} (:expected-blocks updated)))
      (is (= 1 (count effects)))
      (is (= "peer-a" (get-in (first effects) [:send :address])))
      (is (= {:id "data-a"} (get-in (first effects) [:send :peer-data])))
      (is (bytes? (get-in (first effects) [:send :bytes]))))))

(deftest on-message-choke-requeues-test
  (testing "choke requeues the assigned piece with no effects"
    (let [state (-> (message-state)
                    (assoc-in [:active-peers "peer-a" :assigned-piece] 0)
                    (assoc-in [:download :piece-state]
                              (:ok (pieces/mark-in-flight
                                    (pieces/initial-piece-state 2) 0))))
          [updated effects] (download/on-message
                             state
                             {:address "peer-a" :message (peer/->Choke)}
                             (message-ctx (two-piece-hashes)))]
      (is (= [] effects))
      (is (contains? (get-in updated [:download :piece-state :needed]) 0))
      (is (nil? (get-in updated [:active-peers "peer-a" :assigned-piece]))))))

(deftest on-message-have-plans-request-test
  (testing "have from an unchoked peer with nothing available triggers a request"
    (let [bare (-> (peer-state/initial-peer-state 2)
                   (peer-state/set-am-interested true)
                   (peer-state/apply-message (peer/->Unchoke)))
          state (assoc-in (message-state) [:active-peers "peer-a" :peer-state] bare)
          [updated effects] (download/on-message
                             state
                             {:address "peer-a" :message (peer/->Have 1)}
                             (message-ctx (two-piece-hashes)))]
      (is (= 1 (get-in updated [:active-peers "peer-a" :assigned-piece])))
      (is (= 1 (count effects))))))

(deftest on-message-piece-buffers-partial-test
  (testing "a non-final block is buffered with no effects"
    (let [ctx {:piece-hashes (two-piece-hashes)
               :piece-length 16385 :total-length 32770 :total-pieces 2}
          state (-> (message-state)
                    (assoc-in [:active-peers "peer-a" :assigned-piece] 0)
                    (assoc :expected-blocks {"peer-a" 2})
                    (assoc-in [:download :piece-state]
                              (:ok (pieces/mark-in-flight
                                    (pieces/initial-piece-state 2) 0))))
          [updated effects] (download/on-message
                             state
                             {:address "peer-a"
                              :message (peer/->Piece 0 0 (byte-array 16384))}
                             ctx)]
      (is (= [] effects))
      (is (= 1 (count (get (:blocks-received updated) "peer-a"))))
      (is (= 0 (get-in updated [:active-peers "peer-a" :assigned-piece]))))))

(deftest on-message-piece-verified-writes-and-requests-next-test
  (testing "a complete verified piece plans a write plus the next request"
    (testing "marking waits for the write: the piece stays in-flight in planned state"
      (let [state (-> (message-state)
                      (assoc-in [:active-peers "peer-a" :assigned-piece] 0)
                      (assoc :expected-blocks {"peer-a" 1})
                      (assoc-in [:download :piece-state]
                                (:ok (pieces/mark-in-flight
                                      (pieces/initial-piece-state 2) 0))))
            [updated effects] (download/on-message
                               state
                               {:address "peer-a"
                                :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}
                               (message-ctx (two-piece-hashes)))
            by-kind (group-by (comp first keys) effects)]
        (is (contains? (get-in updated [:download :piece-state :in-flight]) 0))
        (is (empty? (get-in updated [:download :piece-state :verified])))
        (is (= 0 (get-in (first (by-kind :write-verified)) [:write-verified :piece-idx])))
        (is (= (seq (test-utils/to-bytes "abcd"))
               (seq (get-in (first (by-kind :write-verified)) [:write-verified :data]))))
        (is (= 1 (count (by-kind :send))))
        (is (= 1 (get-in updated [:active-peers "peer-a" :assigned-piece])))))))

(deftest on-message-piece-assembly-failure-requeues-test
  (testing "blocks with a gap requeue the piece with no effects"
    (let [state (-> (message-state)
                    (assoc-in [:active-peers "peer-a" :assigned-piece] 0)
                    (assoc :expected-blocks {"peer-a" 1})
                    (assoc-in [:download :piece-state]
                              (:ok (pieces/mark-in-flight
                                    (pieces/initial-piece-state 2) 0))))
          [updated effects] (download/on-message
                             state
                             {:address "peer-a"
                              :message (peer/->Piece 0 5 (byte-array 1))}
                             (message-ctx (two-piece-hashes)))]
      (is (= [] effects))
      (is (contains? (get-in updated [:download :piece-state :needed]) 0))
      (is (nil? (get-in updated [:active-peers "peer-a" :assigned-piece]))))))

(deftest on-message-piece-verify-failure-requeues-test
  (testing "a hash mismatch requeues the piece with no effects"
    (let [wrong-hashes [(bencode/sha1-hash (test-utils/to-bytes "xxxx"))
                        (bencode/sha1-hash (test-utils/to-bytes "efgh"))]
          state (-> (message-state)
                    (assoc-in [:active-peers "peer-a" :assigned-piece] 0)
                    (assoc :expected-blocks {"peer-a" 1})
                    (assoc-in [:download :piece-state]
                              (:ok (pieces/mark-in-flight
                                    (pieces/initial-piece-state 2) 0))))
          [updated effects] (download/on-message
                             state
                             {:address "peer-a"
                              :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}
                             (message-ctx wrong-hashes))]
      (is (= [] effects))
      (is (contains? (get-in updated [:download :piece-state :needed]) 0))
      (is (nil? (get-in updated [:active-peers "peer-a" :assigned-piece]))))))

(deftest on-message-unknown-peer-and-keepalive-noop-test
  (testing "unknown peers and keep-alives return the state untouched"
    (let [state (message-state)
          ctx (message-ctx (two-piece-hashes))]
      (is (= [state []] (download/on-message
                         state
                         {:address "ghost" :message (peer/->Unchoke)}
                         ctx)))
      (is (= [state []] (download/on-message
                         state
                         {:address "peer-a" :message (peer/->KeepAlive)}
                         ctx))))))

;; End-to-end through the real coordinator loop (issue #2.4): one mock
;; peer serves bitfield -> unchoke -> both pieces; the download must
;; reach :completed with both pieces verified and written.

(deftest run-download-completes-with-mock-swarm-test
  (testing "scripted swarm messages drive run-download to :completed"
    (let [piece-0-bytes (test-utils/to-bytes "abcd")
          piece-1-bytes (test-utils/to-bytes "efgh")
          info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
          torrent {:info-hash info-hash
                   :info {:pieces [(bencode/sha1-hash piece-0-bytes)
                                   (bencode/sha1-hash piece-1-bytes)]
                          :piece-length 4
                          :name "swarm.bin"
                          :length 8}}
          started (assoc (download/initial-download (mock-time/create) torrent "/out" "swarm")
                         :state :downloading
                         :peers #{{:address "10.0.0.9:6881"}})
          net (mock-net/create
               {:handshake-response {:ok {:info-hash info-hash
                                          :peer-id (byte-array 20)}}
                :receive-responses (atom [{:ok (peer/->Bitfield
                                                (byte-array [(unchecked-byte 0xC0)]))}
                                          {:ok (peer/->Unchoke)}
                                          {:ok (peer/->Piece 0 0 piece-0-bytes)}
                                          {:ok (peer/->Piece 1 0 piece-1-bytes)}])})
          disk (mock-disk/create)
          result (deref (future (download/run-download {:network-port net
                                                        :disk-port disk
                                                        :time-port (mock-time/create)
                                                        :config {}}
                                                       started))
                        15000 :timed-out)]
      (is (not= :timed-out result))
      (is (= :completed (:state result)))
      (is (= 2 (pieces/verified-count (:piece-state result))))
      (is (= (seq piece-0-bytes) (seq (mock-disk/get-piece disk 0))))
      (is (= (seq piece-1-bytes) (seq (mock-disk/get-piece disk 1)))))))

(deftest run-download-fails-on-output-layout-error-test
  (testing "a failed layout init fails the download instead of reporting completion"
    (let [info {:pieces [] :piece-length 4 :length 0 :name "empty.bin"}
          torrent {:info-hash (byte-array 20) :info info}
          started (assoc (download/initial-download (mock-time/create) torrent "/out" "init-fail")
                         :state :downloading)
          disk (mock-disk/create {:output-init-error {:error :write-error
                                                      :message "no space"}})
          result (download/run-download {:network-port (mock-net/create)
                                         :disk-port disk
                                         :time-port (mock-time/create)
                                         :config {}}
                                        started)]
      (is (= :failed (:state result)))
      (is (= :disk-error (get-in result [:error :reason])))
      (is (re-find #"no space" (get-in result [:error :message])))
      (is (empty? @(:layouts-initialized disk))))))

(deftest run-download-initializes-layout-before-completing-test
  (testing "a zero-piece download materializes its output layout before completing"
    (let [info {:pieces [] :piece-length 4 :length 0 :name "empty.bin"}
          torrent {:info-hash (byte-array 20) :info info}
          started (assoc (download/initial-download (mock-time/create) torrent "/out" "empty")
                         :state :downloading)
          disk (mock-disk/create)
          result (download/run-download {:network-port (mock-net/create)
                                         :disk-port disk
                                         :time-port (mock-time/create)
                                         :config {}}
                                        started)]
      (is (= :completed (:state result)))
      (is (= 1 (count @(:layouts-initialized disk)))))))

(deftest run-download-fails-on-uncompilable-layout-test
  (testing "info that cannot compile fails before the port is reached"
    (let [info {:pieces [] :piece-length 4 :length 8}
          torrent {:info-hash (byte-array 20) :info info}
          started (assoc (download/initial-download (mock-time/create) torrent "/out" "no-name")
                         :state :downloading)
          disk (mock-disk/create)
          result (download/run-download {:network-port (mock-net/create)
                                         :disk-port disk
                                         :time-port (mock-time/create)
                                         :config {}}
                                        started)]
      (is (= :failed (:state result)))
      (is (= :disk-error (get-in result [:error :reason])))
      ;; The compile branch's prefix, not just "output layout": the pre-compile
      ;; path also produced :failed/:disk-error with "Failed to initialize
      ;; output layout: Invalid output layout..." and an empty init record, so
      ;; only the prefix discriminates compile-before-port from port-refusal.
      (is (re-find #"^Failed to compile output layout" (get-in result [:error :message])))
      (is (empty? @(:layouts-initialized disk))))))

(deftest run-download-passes-compiled-layout-to-port-test
  (testing "the port receives the layout compiled once, not raw info"
    (let [info {:pieces [] :piece-length 4 :length 0 :name "empty.bin"}
          torrent {:info-hash (byte-array 20) :info info}
          started (assoc (download/initial-download (mock-time/create) torrent "/out" "empty-layout")
                         :state :downloading)
          disk (mock-disk/create)
          result (download/run-download {:network-port (mock-net/create)
                                         :disk-port disk
                                         :time-port (mock-time/create)
                                         :config {}}
                                        started)]
      (is (= :completed (:state result)))
      (is (= [(:ok (torrent/compile-output-layout info))]
             (mapv :layout @(:layouts-initialized disk)))))))

(deftest mock-init-refuses-uncompiled-layout-test
  (testing "the mock mirrors the real port: raw info or nil is :invalid-info, unrecorded"
    (let [disk (mock-disk/create)]
      (doseq [bad [nil
                   {:pieces [] :piece-length 4 :length 8 :name "raw.bin"}
                   {:sizes {} :files []}
                   {:sizes {}
                    :files [{:path ["t" "a"] :length 4 :start 0}]
                    :total 4 :piece-length 4}]]
        (let [result (disk/initialize-output-layout disk bad "/out")]
          (is (= :invalid-info (:error result)) (str "init " (pr-str bad)))))
      (is (empty? @(:layouts-initialized disk))))))

(deftest mock-write-surfaces-configured-write-error-test
  (testing "the :write-error fallback reaches output writes, not just cache writes"
    (let [info {:name "t" :piece-length 4 :length 4}
          layout (:ok (torrent/compile-output-layout info))
          disk (mock-disk/create {:write-error {:error :write-error
                                                :message "disk full"}})
          result (disk/write-output-piece disk layout "/out" 0
                                          (test-utils/to-bytes "abcd"))]
      (is (= :write-error (:error result)))
      (is (empty? (mock-disk/get-output-layouts disk))))))

(deftest mock-write-refuses-uncompiled-layout-test
  (testing "mock writes refuse uncompiled shapes like the real port"
    (let [disk (mock-disk/create)
          bad {:sizes {}
               :files [{:path ["t" "a"] :length 4 :start 0}]
               :total 4 :piece-length 4}
          result (disk/write-output-piece disk bad "/out" 0
                                          (test-utils/to-bytes "abcd"))]
      (is (= :invalid-info (:error result)))
      (is (empty? (mock-disk/get-output-layouts disk)))
      (is (nil? (mock-disk/get-output-piece disk 0))))))

(deftest mock-write-returns-write-error-on-non-bytes-test
  (testing "non-byte-array input yields the :write-error envelope like the real port, never a throw"
    ;; DiskPortImpl wraps its whole write body in try/catch, so a bad bytes
    ;; argument comes back as {:error :write-error}. The mock must mirror
    ;; that envelope or tests cannot drive the orchestration error path.
    (let [info {:name "t" :piece-length 4 :length 4}
          layout (:ok (torrent/compile-output-layout info))
          disk (mock-disk/create)
          result (disk/write-output-piece disk layout "/out" 0 "not-bytes")]
      (is (= :write-error (:error result)))
      (is (empty? (mock-disk/get-output-layouts disk))))))

(deftest mock-write-mirrors-real-gate-test
  (testing "mock write gates mirror the real port: span derivation, O(1) shape, touched-path membership"
    ;; A shape-valid layout whose starts do not chain fails
    ;; consistent-output-layout?, but the real port's per-piece write
    ;; accepts it — full invariants are enforced once at init, not per
    ;; write. The mock must accept what the real port accepts, or tests
    ;; drive refusal paths the real port never produces (and vice versa).
    (let [layout {:sizes {["t" "a"] 4 ["t" "b"] 4}
                  :files [{:path ["t" "a"] :length 4 :start 0}
                          {:path ["t" "b"] :length 4 :start 8}]
                  :total 12 :piece-length 4}
          disk (mock-disk/create)]
      (is (false? (disk/consistent-output-layout? layout)))
      (let [result (disk/write-output-piece disk layout "/out" 0
                                            (test-utils/to-bytes "abcd"))]
        (is (= {:ok :written} result))
        (is (= [layout] (mock-disk/get-output-layouts disk))))
      (testing "a piece falling in the gap between entries is refused like the real port"
        (let [result (disk/write-output-piece disk layout "/out" 1
                                              (test-utils/to-bytes "efgh"))]
          (is (= :invalid-info (:error result)))
          (is (nil? (mock-disk/get-output-piece disk 1))))))))

;; Scripted events-ch through the extracted loop (issue #2.4): feed
;; run-coordinator a pre-loaded channel and assert piece-state
;; transitions, including requeue on choke and disconnect.

(defn- two-piece-torrent []
  {:info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
   :info {:pieces [(bencode/sha1-hash (test-utils/to-bytes "abcd"))
                   (bencode/sha1-hash (test-utils/to-bytes "efgh"))]
          :piece-length 4
          :name "loop.bin"
          :length 8}})

(defn- loop-peer-state []
  (-> (peer-state/initial-peer-state 2)
      (peer-state/set-am-interested true)
      (peer-state/apply-message (peer/->Unchoke))
      (peer-state/mark-piece-available 0)
      (peer-state/mark-piece-available 1)))

(defn- scripted-run [events download disk & [opts]]
  (let [torrent (:torrent download)
        info (:info torrent)
        events-ch (async/chan 16)
        env {:message-ctx {:piece-hashes (:pieces info)
                           :piece-length (:piece-length info)
                           :total-length (:length info)
                           :total-pieces (count (:pieces info))}
             :output-layout (:ok (torrent/compile-output-layout info))
             :ports {:network-port (or (:net opts) (mock-net/create))
                     :disk-port disk
                     :time-port (mock-time/create)}
             :conn-stats (or (:conn-stats opts) (atom {:connected 0 :failed 0}))
             :total-attempted 1}
        state {:download download
               :active-peers {}
               :blocks-received {}
               :expected-blocks {}
               :pending-dials (get opts :pending-dials)}]
    (doseq [event events]
      (async/>!! events-ch event))
    (when (get opts :close? true)
      (async/close! events-ch))
    (deref (future (download/run-coordinator state events-ch env))
           (get opts :timeout 15000) :timed-out)))

(defn- loop-download []
  (assoc (download/initial-download (mock-time/create) (two-piece-torrent) "/out" "loop")
         :state :downloading))

(deftest run-coordinator-verifies-pieces-test
  (testing "connected -> unchoke -> both pieces completes the download"
    (let [disk (mock-disk/create)
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                  {:type :peer-message :address "peer-a"
                   :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}
                  {:type :peer-message :address "peer-a"
                   :message (peer/->Piece 1 0 (test-utils/to-bytes "efgh"))}]
          result (scripted-run events (loop-download) disk)]
      (is (not= :timed-out result))
      (is (= :completed (:state result)))
      (is (= 2 (pieces/verified-count (:piece-state result))))
      (is (= (seq (test-utils/to-bytes "abcd")) (seq (mock-disk/get-piece disk 0))))
      (is (= (seq (test-utils/to-bytes "efgh")) (seq (mock-disk/get-piece disk 1)))))))

(deftest run-coordinator-completion-closes-peers-test
  (testing "a completed download closes every active connection"
    (let [disk (mock-disk/create)
          net (mock-net/create)
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                  {:type :peer-message :address "peer-a"
                   :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}
                  {:type :peer-message :address "peer-a"
                   :message (peer/->Piece 1 0 (test-utils/to-bytes "efgh"))}]
          result (scripted-run events (loop-download) disk {:net net})]
      (is (not= :timed-out result))
      (is (= :completed (:state result)))
      (is (= #{{:id "data-a"}} (mock-net/closed-peers net))))))

(deftest run-coordinator-choke-requeues-through-loop-test
  (testing "choke mid-piece returns the piece to needed"
    (let [disk (mock-disk/create)
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                  {:type :peer-message :address "peer-a" :message (peer/->Choke)}]
          result (scripted-run events (loop-download) disk)]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (contains? (get-in result [:piece-state :needed]) 0))
      (is (empty? (get-in result [:piece-state :in-flight]))))))

(deftest run-coordinator-disconnect-requeues-through-loop-test
  (testing "disconnect mid-piece returns the piece to needed"
    (let [disk (mock-disk/create)
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                  {:type :peer-disconnected :address "peer-a" :reason "boom"}]
          result (scripted-run events (loop-download) disk)]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (= :no-peers (get-in result [:error :reason])))
      (is (contains? (get-in result [:piece-state :needed]) 0))
      (is (empty? (get-in result [:piece-state :in-flight]))))))

(deftest run-coordinator-closed-channel-fails-no-peers-test
  (testing "a closed event channel fails the download instead of parking"
    (let [disk (mock-disk/create)
          result (scripted-run [] (loop-download) disk)]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (= :no-peers (get-in result [:error :reason])))
      (is (= "All peers disconnected" (get-in result [:error :message]))))))

;; Issue #32: the worker-exit signal must close the event channel, so
;; the coordinator's nil? branch terminates the download instead of
;; parking. Raced against a timeout so a regression fails, never hangs.

(deftest watch-workers-closes-events-when-all-done-test
  (testing "events-ch closes once every worker channel closes"
    (let [worker-a (async/chan 1)
          worker-b (async/chan 1)
          events-ch (async/chan 16)
          _ (download/watch-workers! [worker-a worker-b] events-ch)]
      (async/close! worker-a)
      (async/close! worker-b)
      (let [[value winner] (async/alts!! [events-ch (async/timeout 3000)])]
        (is (= events-ch winner))
        (is (nil? value)))))
  (testing "zero workers closes immediately instead of parking"
    (let [events-ch (async/chan 16)
          _ (download/watch-workers! [] events-ch)
          [_ winner] (async/alts!! [events-ch (async/timeout 3000)])]
      (is (= events-ch winner)))))

;; Issue #32 acceptance: a worker set that all exit drives the
;; coordinator to :failed/:no-peers instead of parking. No disconnect
;; events are enqueued, so only the watcher closing events-ch (the nil?
;; branch) can end the run — a missing watcher loses the timeout race.
;; Raced against a timeout so a regression fails instead of hanging.
(deftest run-coordinator-worker-exits-fail-no-peers-test
  (testing "an exited worker set ends the download with :no-peers"
    (let [disk (mock-disk/create)
          torrent (two-piece-torrent)
          info (:info torrent)
          events-ch (async/chan 16)
          worker-a (async/chan 1)
          worker-b (async/chan 1)
          _ (download/watch-workers! [worker-a worker-b] events-ch)
          env {:message-ctx {:piece-hashes (:pieces info)
                             :piece-length (:piece-length info)
                             :total-length (:length info)
                             :total-pieces (count (:pieces info))}
               :ports {:network-port (mock-net/create)
                       :disk-port disk
                       :time-port (mock-time/create)}
               :conn-stats (atom {:connected 0 :failed 0})
               :total-attempted 2}
          state {:download (loop-download)
                 :active-peers {}
                 :blocks-received {}
                 :expected-blocks {}
                 :pending-dials #{"peer-a" "peer-b"}}
          result-ch (async/thread (download/run-coordinator state events-ch env))]
      ;; Workers exit without producing events: only the watcher can end this.
      (async/close! worker-a)
      (async/close! worker-b)
      (let [[result winner] (async/alts!! [result-ch (async/timeout 5000)])]
        (is (= result-ch winner) "coordinator must win the race; a timeout means it parked")
        (is (= :failed (:state result)))
        (is (= :no-peers (get-in result [:error :reason])))
        (is (= "All peers disconnected" (get-in result [:error :message])))))))

;; Issue #11: the first refused dial must not fail the download while
;; other dials are still in flight; failure waits until every dial has
;; resolved with zero connections.

(deftest run-coordinator-waits-for-pending-dials-test
  (testing "the coordinator starts with every dialed address pending"
    (let [state (download/initial-coordinator-state (loop-download) ["peer-a" "peer-b"])]
      (is (= #{"peer-a" "peer-b"} (:pending-dials state)))
      (is (empty? (:active-peers state)))))
  (testing "first disconnect with dials outstanding keeps waiting, then completes"
    (let [disk (mock-disk/create)
          events [{:type :peer-disconnected :address "peer-a" :reason "Connection refused"}
                  {:type :peer-connected :address "peer-b"
                   :peer-data {:id "data-b"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-b" :message (peer/->Unchoke)}
                  {:type :peer-message :address "peer-b"
                   :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}
                  {:type :peer-message :address "peer-b"
                   :message (peer/->Piece 1 0 (test-utils/to-bytes "efgh"))}]
          result (scripted-run events (loop-download) disk
                               {:pending-dials #{"peer-a" "peer-b"}})]
      (is (not= :timed-out result))
      (is (= :completed (:state result)))
      (is (= 2 (pieces/verified-count (:piece-state result))))))
  (testing "failure only after all dials resolve with zero connections"
    (let [disk (mock-disk/create)
          conn-stats (atom {:connected 0 :failed 0})
          events [{:type :peer-disconnected :address "peer-a" :reason "refused"}
                  {:type :peer-disconnected :address "peer-b" :reason "timeout"}]
          result (scripted-run events (loop-download) disk
                               {:pending-dials #{"peer-a" "peer-b"}
                                :conn-stats conn-stats})]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (= :no-peers (get-in result [:error :reason])))
      (is (= 2 (:failed @conn-stats))))))

(deftest run-coordinator-send-failure-counts-peer-once-test
  (testing "a dropped peer's late disconnect is not double-counted"
    (let [disk (mock-disk/create)
          net (mock-net/create)
          _ (mock-net/add-peer-response net "data-a" nil
                                        {:error :send-failed :message "boom"})
          conn-stats (atom {:connected 0 :failed 0})
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-connected :address "peer-b"
                   :peer-data {:id "data-b"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                  {:type :peer-disconnected :address "peer-a" :reason "socket closed"}
                  {:type :peer-disconnected :address "peer-b" :reason "boom"}]
          result (scripted-run events (loop-download) disk {:net net
                                                            :conn-stats conn-stats})]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (= 2 (:connected @conn-stats)))
      (is (= 2 (:failed @conn-stats))))))

(deftest run-coordinator-send-failure-drops-peer-and-fails-test
  (testing "a dead last peer ends the download instead of waiting forever"
    (let [disk (mock-disk/create)
          net (mock-net/create)
          _ (mock-net/add-peer-response net "data-a" nil
                                        {:error :send-failed :message "boom"})
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}]
          result (scripted-run events (loop-download) disk {:net net
                                                            :close? false
                                                            :timeout 3000})]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (= :no-peers (get-in result [:error :reason])))
      (is (contains? (get-in result [:piece-state :needed]) 0))
      (is (empty? (get-in result [:piece-state :verified])))
      (is (= #{{:id "data-a"}} (mock-net/closed-peers net))))))

(deftest run-coordinator-send-error-requeues-through-loop-test
  (testing "a failed block send returns the piece to needed, nothing strands"
    (let [disk (mock-disk/create)
          net (mock-net/create)
          _ (mock-net/add-peer-response net "data-a" nil
                                        {:error :send-failed :message "boom"})
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}]
          result (scripted-run events (loop-download) disk {:net net})]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (contains? (get-in result [:piece-state :needed]) 0))
      (is (empty? (get-in result [:piece-state :in-flight])))
      (is (nil? (mock-disk/get-piece disk 0))))))

(deftest run-coordinator-write-error-fails-download-test
  (testing "a failed piece write fails the download instead of verifying air"
    (testing "the unwritten piece returns to needed, never to verified"
      (let [disk (mock-disk/create {:write-error {:error :write-error
                                                  :message "disk full"}})
            events [{:type :peer-connected :address "peer-a"
                     :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                    {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                    {:type :peer-message :address "peer-a"
                     :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}]
            result (scripted-run events (loop-download) disk)]
        (is (not= :timed-out result))
        (is (= :failed (:state result)))
        (is (= :disk-error (get-in result [:error :reason])))
        (is (contains? (get-in result [:piece-state :needed]) 0))
        (is (contains? (get-in result [:piece-state :needed]) 1))
        (is (empty? (get-in result [:piece-state :in-flight])))
        (is (empty? (get-in result [:piece-state :verified])))))))

(deftest run-coordinator-write-error-closes-peers-test
  (testing "a fatal write closes every active connection"
    (let [disk (mock-disk/create {:write-error {:error :write-error
                                                :message "disk full"}})
          net (mock-net/create)
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                  {:type :peer-message :address "peer-a"
                   :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}]
          result (scripted-run events (loop-download) disk {:net net})]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (= :disk-error (get-in result [:error :reason])))
      (is (= #{{:id "data-a"}} (mock-net/closed-peers net))))))

(deftest run-coordinator-writes-output-layout-test
  (testing "verified pieces land in the output layout as well as the piece cache"
    (let [disk (mock-disk/create)
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                  {:type :peer-message :address "peer-a"
                   :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}
                  {:type :peer-message :address "peer-a"
                   :message (peer/->Piece 1 0 (test-utils/to-bytes "efgh"))}]
          download (loop-download)
          expected-layout (:ok (torrent/compile-output-layout (:info (:torrent download))))
          result (scripted-run events download disk)]
      (is (not= :timed-out result))
      (is (= :completed (:state result)))
      (is (= (seq (test-utils/to-bytes "abcd")) (seq (mock-disk/get-output-piece disk 0))))
      (is (= (seq (test-utils/to-bytes "efgh")) (seq (mock-disk/get-output-piece disk 1))))
      ;; The coordinator must hand the compiled layout (not raw info) to
      ;; every piece write: reverting the call argument keeps the bytes
      ;; assertions green but fails this one (PR #36 r4115143527).
      (is (= [expected-layout expected-layout] (mock-disk/get-output-layouts disk))))))

(deftest run-coordinator-output-write-error-fails-download-test
  (testing "a failed output-layout write fails the download like a cache write"
    (let [disk (mock-disk/create {:output-write-error {:error :write-error
                                                       :message "output full"}})
          events [{:type :peer-connected :address "peer-a"
                   :peer-data {:id "data-a"} :peer-state (loop-peer-state)}
                  {:type :peer-message :address "peer-a" :message (peer/->Unchoke)}
                  {:type :peer-message :address "peer-a"
                   :message (peer/->Piece 0 0 (test-utils/to-bytes "abcd"))}]
          result (scripted-run events (loop-download) disk)]
      (is (not= :timed-out result))
      (is (= :failed (:state result)))
      (is (= :disk-error (get-in result [:error :reason])))
      (is (contains? (get-in result [:piece-state :needed]) 0))
      (is (empty? (get-in result [:piece-state :verified]))))))

;; ---------------------------------------------------------------------------
;; fdef specs hold generatively (stest/check).
;; Pinned: the pure, total half of this namespace. Excluded on principle,
;; not by accident — the generator cannot conjure a protocol implementation,
;; so anything behind an effect port fails before its :ret is even reached
;; (check on calculate-rate dies in (time/now <generated-long>)):
;; calculate-rate, update-stats-bytes, initial-stats, initial-download,
;; progress (time port); start-download, run-coordinator, run-download,
;; load-persisted-state, persist-download-state (ports, channels, workers);
;; watch-workers! (channels close on worker exit — no generated channel).
;; ---------------------------------------------------------------------------

(deftest fdef-specs-hold-generatively-test
  (testing "every check-grade download fdef holds over generated inputs"
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.orchestration.download/capped-peer-addresses
                      dev.cljtoc.orchestration.download/swarm-exhausted?
                      dev.cljtoc.orchestration.download/can-retry?
                      dev.cljtoc.orchestration.download/has-active-peers?
                      dev.cljtoc.orchestration.download/on-connected
                      dev.cljtoc.orchestration.download/on-disconnected
                      dev.cljtoc.orchestration.download/on-message
                      dev.cljtoc.orchestration.download/requeue-assignment
                      dev.cljtoc.orchestration.download/initial-coordinator-state
                      dev.cljtoc.orchestration.download/handle-no-peers
                      dev.cljtoc.orchestration.download/retry-download
                      dev.cljtoc.orchestration.download/transition-to-failed
                      dev.cljtoc.orchestration.download/get-failed-piece
                      dev.cljtoc.orchestration.download/requeue-piece
                      dev.cljtoc.orchestration.download/handle-piece-verification-failure
                      dev.cljtoc.orchestration.download/handle-peer-disconnect
                      dev.cljtoc.orchestration.download/add-peer
                      dev.cljtoc.orchestration.download/remove-peer]
                    50)]
      (is (empty? failures)
          (str "fdef check failures: " (pr-str failures))))))

;; ============================================================================
;; Resume: materializing verified pieces (issue #30)
;; ============================================================================
;;
;; A saved record carries a verified piece set whose bytes live in the piece
;; cache, not in the output files. Declaring :completed on that record alone
;; hands back files that initialize-output-layout only truncated to length.

(defn- materialize-fixture
  "A 2-piece single-file download with piece 0 verified, its bytes in the
   cache, and piece 1 still needed. Returns {:download :layout :disk}."
  []
  (let [piece-0 (test-utils/to-bytes "abcd")
        info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
        info {:pieces [(bencode/sha1-hash piece-0)
                       (bencode/sha1-hash (test-utils/to-bytes "efgh"))]
              :piece-length 4
              :name "mat.bin"
              :length 8}
        torrent {:info-hash info-hash :info info}
        ;; mark-in-flight takes 0 out of :needed, mark-verified puts it in
        ;; :verified, so :needed is left holding just piece 1.
        piece-state (-> (pieces/initial-piece-state 2)
                        (#(:ok (pieces/mark-in-flight % 0)))
                        (#(:ok (pieces/mark-verified % 0))))
        download (assoc (download/initial-download (mock-time/create)
                                                   torrent
                                                   "/out"
                                                   "mat")
                        :piece-state piece-state)
        disk (mock-disk/create)]
    (disk/write-piece disk 0 piece-0)
    {:download download
     :layout (:ok (torrent/compile-output-layout info))
     :disk disk
     :piece-0 piece-0}))
(deftest materialize-copies-verified-pieces-out-of-the-cache-test
  (testing "a verified piece reaches the output layout on resume"
    (let [{:keys [download layout disk piece-0]}
          (materialize-fixture)
          result (#'download/materialize-verified-pieces
                  download disk layout)]
      (is (not (:error result)))
      (is (= (seq piece-0) (seq (mock-disk/get-output-piece disk 0)))))))

(deftest materialize-keeps-the-verified-set-test
  (testing "materializing does not un-verify the pieces it wrote"
    (let [{:keys [download layout disk]} (materialize-fixture)
          result (#'download/materialize-verified-pieces download disk layout)
          piece-state (:piece-state (:ok result))]
      (is (= #{0} (:verified piece-state)))
      (is (= #{1} (:needed piece-state))))))

(deftest materialize-requeues-a-verified-piece-with-no-cached-bytes-test
  (testing "a record claiming a piece is verified that the cache no longer
            holds cannot be written out. Leaving it verified would let
            complete? answer true and declare a download finished with a
            hole in it, so it goes back to :needed and the swarm re-fetches it"
    (let [{:keys [download layout]} (materialize-fixture)
          ;; A fresh port: the record still claims piece 0 verified, but the
          ;; cache no longer has its bytes.
          disk (mock-disk/create)
          result (#'download/materialize-verified-pieces download disk layout)
          piece-state (:piece-state (:ok result))]
      (is (= #{} (:verified piece-state)))
      (is (= #{0 1} (:needed piece-state)))
      (is (false? (pieces/complete? piece-state))))))

(deftest materialize-requeues-a-verified-piece-with-corrupt-cached-bytes-test
  (testing "cache bytes that fail their torrent hash go back to :needed
            instead of being written into the output. The real port returns
            whatever the cache file holds with no length or hash check, so
            trusting the verified set alone would complete the download with
            corrupt content on disk"
    (let [{:keys [download layout disk]} (materialize-fixture)
          _ (disk/write-piece disk 0 (test-utils/to-bytes "XXXX"))
          result (#'download/materialize-verified-pieces download disk layout)
          piece-state (:piece-state (:ok result))]
      (is (= #{} (:verified piece-state)))
      (is (= #{0 1} (:needed piece-state)))
      (is (nil? (mock-disk/get-output-piece disk 0))))))

(deftest materialize-trusts-the-cache-when-the-record-carries-no-hashes-test
  (testing "without piece hashes there is nothing to verify against, so the
            piece writes through exactly as before the verification step"
    (let [{:keys [download layout disk piece-0]} (materialize-fixture)
          result (#'download/materialize-verified-pieces
                  (dissoc download :torrent) disk layout)]
      (is (not (:error result)))
      (is (= (seq piece-0) (seq (mock-disk/get-output-piece disk 0)))))))

(deftest materialize-fails-when-the-layout-rejects-a-write-test
  (testing "a refused write fails the resume rather than reporting a
            download whose bytes never landed"
    (let [{:keys [download layout]} (materialize-fixture)
          disk (mock-disk/create {:output-write-error {:error :write-error
                                                       :message "disk full"}})
          _ (disk/write-piece disk 0 (test-utils/to-bytes "abcd"))
          result (#'download/materialize-verified-pieces download disk layout)]
      (is (= :write-error (:error result)))
      (is (= "disk full" (:message result))))))

(deftest materialize-fails-when-the-cache-cannot-be-read-test
  (testing "an unreadable piece cache fails the resume instead of reporting
            the piece as verified and moving on"
    (let [{:keys [download layout]} (materialize-fixture)
          disk (mock-disk/create {:read-error {:error :read-error
                                               :message "permission denied"}})
          result (#'download/materialize-verified-pieces download disk layout)]
      (is (= :read-error (:error result)))
      (is (= "permission denied" (:message result))))))

(deftest resume-of-a-fully-verified-download-writes-the-real-content-test
  (testing "a record whose pieces are all verified materializes them from the
            cache into the output file. initialize-output-layout only creates
            the file at its declared length, so before this the download
            reported :completed over an empty file -- the one resume case
            that looks successful and is not (issue #30, PR #24 review)"
    (let [piece-0 (test-utils/to-bytes "abcd")
          piece-1 (test-utils/to-bytes "efgh")
          content (byte-array 8)
          info {:pieces [(bencode/sha1-hash piece-0) (bencode/sha1-hash piece-1)]
                :piece-length 4
                :name "whole.bin"
                :length 8}
          torrent {:info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
                   :info info}
          output-dir (test-utils/temp-dir "resume-out-")
          port (disk-impl/create {:state-dir (test-utils/temp-dir "resume-state-")
                                  :piece-cache-dir (test-utils/temp-dir "resume-cache-")})
          _ (System/arraycopy piece-0 0 content 0 4)
          _ (System/arraycopy piece-1 0 content 4 4)
          piece-state (-> (pieces/initial-piece-state 2)
                          (#(:ok (pieces/mark-in-flight % 0)))
                          (#(:ok (pieces/mark-verified % 0)))
                          (#(:ok (pieces/mark-in-flight % 1)))
                          (#(:ok (pieces/mark-verified % 1))))
          download (assoc (download/initial-download (mock-time/create)
                                                     torrent
                                                     output-dir
                                                     "whole")
                          :piece-state piece-state
                          :state :downloading)
          _ (disk/write-piece port 0 piece-0)
          _ (disk/write-piece port 1 piece-1)
          result (download/run-download {:network-port (mock-net/create)
                                         :disk-port port
                                         :time-port (mock-time/create)
                                         :config {}}
                                        download)
          on-disk (slurp (io/file output-dir "whole.bin"))]
      (is (= :completed (:state result)))
      (is (= (seq content) (seq (test-utils/to-bytes on-disk)))
          "the completed file carries the verified content, not its length"))))

;; ============================================================================
;; Resume: letting a failed download back in (issue #30)
;; ============================================================================

(defn- stranded-download
  "The live run in issue #30: 57 of 2180 pieces verified, one piece left
   in-flight by the run that died, the swarm exhausted, :error set."
  [total-pieces verified in-flight]
  (let [piece-hashes (mapv #(bencode/sha1-hash (test-utils/to-bytes (str "piece-" %)))
                           (range total-pieces))
        piece-length 4
        torrent {:info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
                 :info {:pieces piece-hashes
                        :piece-length piece-length
                        :name "stranded.bin"
                        :length (* total-pieces piece-length)}}
        piece-state (reduce (fn [state piece-index]
                              (if (contains? verified piece-index)
                                (-> state
                                    (#(:ok (pieces/mark-in-flight % piece-index)))
                                    (#(:ok (pieces/mark-verified % piece-index))))
                                (if (contains? in-flight piece-index)
                                  (:ok (pieces/mark-in-flight state piece-index))
                                  state)))
                            (pieces/initial-piece-state total-pieces)
                            (range total-pieces))]
    (assoc (download/initial-download (mock-time/create) torrent "/out" "stranded")
           :state :failed
           :piece-state piece-state
           :peers #{{:address "10.0.0.9:6881" :port 6881}}
           :error {:reason :no-peers :message "All peers disconnected"})))

(deftest resume-download-accepts-a-failed-download-test
  (testing "a download that failed with the swarm exhausted is resumable, so
            the record on disk is not a dead end"
    (let [failed (stranded-download 4 #{0 1} #{})
          result (download/resume-download nil nil failed)]
      (is (= :downloading (get-in result [:ok :state]))))))

(deftest resume-download-keeps-the-verified-pieces-test
  (testing "resuming does not re-request what already verified -- those bytes
            are in the cache and run-download materializes them"
    (let [failed (stranded-download 4 #{0 1 2} #{})
          piece-state (get-in (download/resume-download nil nil failed)
                              [:ok :piece-state])]
      (is (= #{0 1 2} (:verified piece-state)))
      (is (= #{3} (:needed piece-state))))))

(deftest resume-download-requeues-pieces-the-dead-run-left-in-flight-test
  (testing "a piece the previous run had in flight is requested nowhere and
            verified by nobody, so complete? could never reach it. It goes
            back to :needed or the resumed swarm skips it forever"
    (let [failed (stranded-download 4 #{0 1} #{2})
          piece-state (get-in (download/resume-download nil nil failed)
                              [:ok :piece-state])]
      (is (= #{} (:in-flight piece-state)))
      (is (= #{2 3} (:needed piece-state)))
      (is (= 2 (pieces/verified-count piece-state))))))

(deftest resume-download-never-reuses-the-exhausted-peer-set-test
  (testing "the peers on a failed record are the ones that just failed. With
            no network port to re-announce there is nothing fresh to dial, so
            the set is dropped and run-download fails fast on :no-peers
            instead of redialing known-dead addresses"
    (let [failed (stranded-download 4 #{0} #{})
          result (download/resume-download nil nil failed)]
      (is (= #{} (get-in result [:ok :peers]))))))

(deftest resume-download-clears-the-failed-error-test
  (testing "the record leaves :error behind: it is resuming, not failed, and a
            stale :no-peers would be saved over the new run"
    (let [failed (stranded-download 4 #{0} #{})
          result (download/resume-download nil nil failed)]
      (is (nil? (get-in result [:ok :error]))))))

(deftest resume-download-rejects-a-completed-download-test
  (testing "a finished download has nothing to resume"
    (let [done (assoc (stranded-download 4 #{0 1 2 3} #{}) :state :completed)]
      (is (= :not-paused (:error (download/resume-download nil nil done)))))))

(deftest resume-download-re-announces-for-failed-downloads-test
  (testing "a failed download gets a fresh peer list from the tracker, never
            the exhausted one it was saved with"
    (let [failed (stranded-download 4 #{0 1} #{})
          net (mock-net/create {:mock-peers ["10.9.9.1:6881"]})
          result (download/resume-download nil net failed)]
      (is (= :downloading (get-in result [:ok :state])))
      (is (= #{["10.9.9.1:6881" 6881]}
             (set (map (juxt :address :port) (get-in result [:ok :peers]))))))))

(deftest resume-at-one-verified-piece-completes-through-the-swarm-with-real-files-test
  (testing "the issue #30 cycle end to end: a failed record with piece 0
            verified resumes through the mock swarm, pieces 1 and 2 arrive
            over the wire, and every output file is byte-identical to the
            declared content. The real DiskPortImpl proves the bytes, not
            the mock's recording. Piece 1 straddles the file boundary, so
            the assertion also covers span writes during a resumed run"
    (let [piece-bytes [(test-utils/to-bytes "abcd")
                       (test-utils/to-bytes "efgh")
                       (test-utils/to-bytes "ijkl")]
          info {:pieces (mapv bencode/sha1-hash piece-bytes)
                :piece-length 4
                :name "cycle"
                :files [{:path ["a.bin"] :length 6}
                        {:path ["b.bin"] :length 6}]}
          info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
          torrent {:info-hash info-hash :info info}
          output-dir (test-utils/temp-dir "cycle-out-")
          disk-port (disk-impl/create {:state-dir (test-utils/temp-dir "cycle-state-")
                                       :piece-cache-dir (test-utils/temp-dir "cycle-cache-")})
          piece-state (-> (pieces/initial-piece-state 3)
                          (#(:ok (pieces/mark-in-flight % 0)))
                          (#(:ok (pieces/mark-verified % 0))))
          failed (assoc (download/initial-download (mock-time/create)
                                                   torrent
                                                   output-dir
                                                   "cycle")
                        :state :failed
                        :piece-state piece-state
                        :peers #{{:address "10.0.0.9:6881" :port 6881}}
                        :error {:reason :no-peers :message "swarm exhausted"})
          _ (disk/write-piece disk-port 0 (nth piece-bytes 0))
          net (mock-net/create
               {:mock-peers ["10.0.0.9:6881"]
                :handshake-response {:ok {:info-hash info-hash
                                          :peer-id (byte-array 20)}}
                :receive-responses (atom [{:ok (peer/->Bitfield
                                                (byte-array [(unchecked-byte 0x60)]))}
                                          {:ok (peer/->Unchoke)}
                                          {:ok (peer/->Piece 1 0 (nth piece-bytes 1))}
                                          {:ok (peer/->Piece 2 0 (nth piece-bytes 2))}])})
          resumed (:ok (download/resume-download nil net failed))
          result (deref (future (download/run-download {:network-port net
                                                        :disk-port disk-port
                                                        :time-port (mock-time/create)
                                                        :config {}}
                                                       resumed))
                        15000 :timed-out)
          read-file (fn [name]
                      (seq (java.nio.file.Files/readAllBytes
                            (.toPath (io/file output-dir "cycle" name)))))]
      (is (not= :timed-out result))
      (is (= :completed (:state result)))
      (is (= 3 (pieces/verified-count (:piece-state result))))
      (is (= (seq (test-utils/to-bytes "abcdef")) (read-file "a.bin")))
      (is (= (seq (test-utils/to-bytes "ghijkl")) (read-file "b.bin"))))))

(deftest run-download-fails-when-verified-pieces-cannot-be-materialized-test
  (testing "a layout that refuses the write during resume fails the download
            with :disk-error instead of reporting the pieces as safe"
    (let [piece-0 (test-utils/to-bytes "abcd")
          info {:pieces [(bencode/sha1-hash piece-0)
                         (bencode/sha1-hash (test-utils/to-bytes "efgh"))]
                :piece-length 4
                :name "matfail.bin"
                :length 8}
          torrent {:info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
                   :info info}
          piece-state (-> (pieces/initial-piece-state 2)
                          (#(:ok (pieces/mark-in-flight % 0)))
                          (#(:ok (pieces/mark-verified % 0))))
          download (assoc (download/initial-download (mock-time/create)
                                                     torrent
                                                     "/out"
                                                     "matfail")
                          :piece-state piece-state
                          :state :downloading)
          disk (mock-disk/create {:output-write-error {:error :write-error
                                                       :message "disk full"}})
          _ (disk/write-piece disk 0 piece-0)
          result (download/run-download {:network-port (mock-net/create)
                                         :disk-port disk
                                         :time-port (mock-time/create)
                                         :config {}}
                                        download)]
      (is (= :failed (:state result)))
      (is (= :disk-error (get-in result [:error :reason])))
      (is (re-find #"^Failed to materialize verified pieces"
                   (get-in result [:error :message]))))))

(deftest materialize-keeps-requeue-progress-when-a-later-write-fails-test
  (testing "a write that fails after an earlier piece was requeued carries
            the in-progress record. Failing with the original would re-claim
            the holey piece as verified, and the next resume would repeat
            the same dance instead of fetching it"
    (let [{:keys [download layout]} (materialize-fixture)
          both-verified (assoc download :piece-state
                               (-> (:piece-state download)
                                   (#(:ok (pieces/mark-in-flight % 1)))
                                   (#(:ok (pieces/mark-verified % 1)))))
          disk (mock-disk/create {:output-write-error {:error :write-error
                                                       :message "disk full"}})
          _ (disk/write-piece disk 1 (test-utils/to-bytes "efgh"))
          result (#'download/materialize-verified-pieces both-verified disk layout)
          failed-record (:download result)]
      (is (= :write-error (:error result)))
      (is (= #{0} (:needed (:piece-state failed-record))))
      (is (= #{1} (:verified (:piece-state failed-record)))))))

(deftest paused-download-resumes-through-the-swarm-with-real-files-test
  (testing "the same cycle from a :paused record: one piece verified, the
            other arrives over the wire, both files byte-identical. Pause
            and failure share the resume path past the entry guard, so this
            pins the spec's paused/failed wording rather than assuming it"
    (let [piece-bytes [(test-utils/to-bytes "abcd")
                       (test-utils/to-bytes "efgh")]
          info {:pieces (mapv bencode/sha1-hash piece-bytes)
                :piece-length 4
                :name "resumed"
                :length 8}
          info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
          torrent {:info-hash info-hash :info info}
          output-dir (test-utils/temp-dir "paused-out-")
          disk-port (disk-impl/create {:state-dir (test-utils/temp-dir "paused-state-")
                                       :piece-cache-dir (test-utils/temp-dir "paused-cache-")})
          piece-state (-> (pieces/initial-piece-state 2)
                          (#(:ok (pieces/mark-in-flight % 0)))
                          (#(:ok (pieces/mark-verified % 0))))
          paused (assoc (download/initial-download (mock-time/create)
                                                   torrent
                                                   output-dir
                                                   "paused")
                        :state :paused
                        :piece-state piece-state
                        :peers #{})
          _ (disk/write-piece disk-port 0 (nth piece-bytes 0))
          net (mock-net/create
               {:mock-peers ["10.0.0.9:6881"]
                :handshake-response {:ok {:info-hash info-hash
                                          :peer-id (byte-array 20)}}
                :receive-responses (atom [{:ok (peer/->Bitfield
                                                (byte-array [(unchecked-byte 0x40)]))}
                                          {:ok (peer/->Unchoke)}
                                          {:ok (peer/->Piece 1 0 (nth piece-bytes 1))}])})
          resumed (:ok (download/resume-download nil net paused))
          result (deref (future (download/run-download {:network-port net
                                                        :disk-port disk-port
                                                        :time-port (mock-time/create)
                                                        :config {}}
                                                       resumed))
                        15000 :timed-out)
          on-disk (seq (java.nio.file.Files/readAllBytes
                        (.toPath (io/file output-dir "resumed"))))]
      (is (not= :timed-out result))
      (is (= :completed (:state result)))
      (is (= 2 (pieces/verified-count (:piece-state result))))
      (is (= (seq (test-utils/to-bytes "abcdefgh")) on-disk)))))
