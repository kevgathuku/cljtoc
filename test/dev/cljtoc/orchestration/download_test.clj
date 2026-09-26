(ns dev.cljtoc.orchestration.download-test
  "Unit tests for download orchestration."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.domain.pieces :as pieces]
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
                   :info {:pieces ["h1" "h2"] :piece-length 262144 :length 524288}}
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
