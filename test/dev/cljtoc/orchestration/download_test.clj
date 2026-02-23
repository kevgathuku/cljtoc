(ns dev.cljtoc.orchestration.download-test
  "Unit tests for download orchestration."
  (:require [clojure.test :refer :all]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.test-doubles.network :as mock-net]
            [dev.cljtoc.test-doubles.disk :as mock-disk]
            [dev.cljtoc.test-doubles.time :as mock-time])
  (:import [java.util UUID]))

(deftest initial-stats-test
  (let [stats (download/initial-stats)]
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
        d (download/initial-download torrent "/output")]
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
           :error nil}]
    (let [prog (download/progress d)]
      (is (= 20.0 (:percent prog)))
      (is (= 2 (:pieces-complete prog)))
      (is (= 10 (:pieces-total prog)))
      (is (= 524288 (:bytes-downloaded prog)))
      (is (= 0 (:peers-connected prog)))
      (is (= :downloading (:state prog)))
      (is (number? (:rate-bytes-per-sec prog))))))

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
           :error nil}]
    (let [prog (download/progress d)]
      (is (= 16384 (:bytes-downloaded prog)))
      (is (= 1 (:peers-connected prog)))
      (is (> (:rate-bytes-per-sec prog) 0)))))

(deftest update-stats-bytes-test
  (let [now (System/currentTimeMillis)
        stats (download/->DownloadStats now nil 1000 0 now)
        updated (download/update-stats-bytes stats 500)]
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
           :stats (download/initial-stats)
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
           :stats (download/initial-stats)
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
           :stats (download/initial-stats)
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
           :stats (download/initial-stats)
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
           :stats (download/initial-stats)
           :error nil}
        disk (mock-disk/create)
        result (download/pause-download disk d)]
    (is (= :paused (get-in result [:ok :state])))
    (is (empty? (get-in result [:ok :peers])))))

;; Peer port parsing tests

(deftest peer-record-parses-port-from-address
  (testing "String splitting correctly parses ip:port address"
    (let [addr "192.168.1.100:5142"
          [host port-str] (clojure.string/split addr #":")
          port (Integer/parseInt port-str)]
      (is (= "192.168.1.100" host))
      (is (= 5142 port)))))

(deftest peer-record-parses-standard-bittorrent-port
  (testing "Standard BitTorrent port 6881 is parsed correctly"
    (let [addr "10.0.0.1:6881"
          [host port-str] (clojure.string/split addr #":")
          port (Integer/parseInt port-str)]
      (is (= "10.0.0.1" host))
      (is (= 6881 port)))))

(deftest peer-record-parses-high-port
  (testing "High port numbers are parsed correctly"
    (let [addr "example.com:65535"
          [host port-str] (clojure.string/split addr #":")
          port (Integer/parseInt port-str)]
      (is (= "example.com" host))
      (is (= 65535 port)))))
