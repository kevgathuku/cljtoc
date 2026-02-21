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
        download (download/initial-download torrent "/output")]
    (is (some? (:id download)))
    (is (= torrent (:torrent download)))
    (is (= :starting (:state download)))
    (is (= "/output" (:output-dir download)))
    (is (some? (:piece-state download)))
    (is (empty? (:peers download)))))

(deftest progress-test
  (let [torrent {:info-hash (byte-array 20)
                :name "test.torrent"
                :piece-length 262144
                :pieces (byte-array (* 20 10))
                :length 2621440
                :files []}
        piece-state (pieces/initial-piece-state 10)
        piece-state (-> piece-state
                       (pieces/mark-in-flight 0)
                       :ok
                       (pieces/mark-in-flight 1)
                       :ok
                       (pieces/mark-verified 0)
                       :ok
                       (pieces/mark-verified 1)
                       :ok)
        now (System/currentTimeMillis)
        stats (download/->DownloadStats now nil 524288 0 now)
        download {:id (UUID/randomUUID)
                  :torrent torrent
                  :piece-state piece-state
                  :peers #{}
                  :state :downloading
                  :output-dir "/output"
                  :stats stats
                  :error nil}]
    (let [prog (download/progress download)]
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
        download {:id (UUID/randomUUID)
                  :torrent torrent
                  :piece-state piece-state
                  :peers #{{:id "peer1" :address "127.0.0.1" :port 6881}}
                  :state :downloading
                  :output-dir "/output"
                  :stats stats
                  :error nil}]
    (let [prog (download/progress download)]
      (is (= 16384 (:bytes-downloaded prog)))
      (is (= 1 (:peers-connected prog)))
      (is (> (:rate-bytes-per-sec prog) 0)))))

(deftest update-stats-bytes-test
  (let [now (System/currentTimeMillis)
        stats (download/->DownloadStats now nil 1000 0 now)
        updated (download/update-stats-bytes stats 500)]
    (is (= 1500 (:bytes-downloaded updated)))
    (is (>= (:last-update updated) now))))

(deftest pause-download-test
  (let [torrent {:info-hash (byte-array 20)
                :name "test.torrent"
                :piece-length 262144
                :pieces (byte-array (* 20 10))
                :length 2621440
                :files []}
        piece-state (pieces/initial-piece-state 10)
        stats (download/->DownloadStats (System/currentTimeMillis) nil 524288 0 (System/currentTimeMillis))
        download {:id (UUID/randomUUID)
                  :torrent torrent
                  :piece-state piece-state
                  :peers #{}
                  :state :downloading
                  :output-dir "/output"
                  :stats stats
                  :error nil}]
    (let [result (download/pause-download download)]
      (is (= :paused (get-in result [:ok :state])))
      (is (= 0 (get-in result [:ok :pieces-complete])))))
  
  (testing "pause when not running"
    (let [download {:state :idle}]
      (is (= :not-running (:error (download/pause-download download)))))))

(deftest resume-download-test
  (let [torrent {:info-hash (byte-array 20)
                :name "test.torrent"
                :piece-length 262144
                :pieces (byte-array (* 20 10))
                :length 2621440
                :files []}
        piece-state (pieces/initial-piece-state 10)
        stats (download/->DownloadStats (System/currentTimeMillis) nil 524288 0 (System/currentTimeMillis))
        download {:id (UUID/randomUUID)
                  :torrent torrent
                  :piece-state piece-state
                  :peers #{{:id "peer1" :address "127.0.0.1" :port 6881}}
                  :state :paused
                  :output-dir "/output"
                  :stats stats
                  :error nil}]
    (let [result (download/resume-download download)]
      (is (= :downloading (get-in result [:ok :state])))
      (is (= 1 (get-in result [:ok :peers-connected])))))
  
  (testing "resume when not paused"
    (let [download {:state :downloading}]
      (is (= :not-paused (:error (download/resume-download download)))))))

(deftest stop-download-test
  (let [torrent {:info-hash (byte-array 20)
                :name "test.torrent"
                :piece-length 262144
                :pieces (byte-array (* 20 10))
                :length 2621440
                :files []}
        piece-state (pieces/initial-piece-state 10)
        stats (download/->DownloadStats (System/currentTimeMillis) nil 524288 0 (System/currentTimeMillis))
        download {:id (UUID/randomUUID)
                  :torrent torrent
                  :piece-state piece-state
                  :peers #{{:id "peer1"}}
                  :state :downloading
                  :output-dir "/output"
                  :stats stats
                  :error nil}
        stopped (download/stop-download download)]
    (is (= :idle (:state stopped)))
    (is (empty? (:peers stopped)))))
