(ns dev.cljtoc.orchestration.session-test
  "Tests for the session value that holds several downloads.

   Seam: the session starts through mock ports, so two torrents start
   with no network and no real disk. The running lives in the
   supervision layer; see dev.cljtoc.supervision.session-supervisor-test."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.orchestration.session :as session]
            [dev.cljtoc.test-doubles.disk :as mock-disk]
            [dev.cljtoc.test-doubles.network :as mock-net]
            [dev.cljtoc.test-doubles.time :as mock-time]
            [dev.cljtoc.test-utils :as test-utils]))

(defn- manager
  [network-port disk-port]
  {:network-port network-port
   :disk-port disk-port
   :time-port (mock-time/create)
   :config {}})

(deftest start-torrents-starts-every-path-and-reports-failures-test
  (testing "two torrents become two downloads; a bad path is reported, not fatal"
    (let [disk (mock-disk/create
                {:torrent-data {"/a.torrent" {:info-hash (byte-array 20)
                                              :info {:pieces ["h1" "h2"]}}
                                "/b.torrent" {:info-hash (byte-array 20)
                                              :info {:pieces ["h1" "h2"]}}}})
          net (mock-net/create)
          started (session/start-torrents (session/session (manager net disk))
                                          ["/a.torrent" "/missing.torrent" "/b.torrent"]
                                          "/out")]
      (is (= ["a" "b"] (map :id (session/downloads started))))
      (is (= ["/a.torrent" "/b.torrent"] (map :torrent-path (session/downloads started)))
          "each download remembers the path it started from, for persistence")
      (is (= ["/missing.torrent"] (map :torrent-path (session/failures started)))))))

(deftest start-torrents-refuses-a-basename-collision-test
  (testing "two paths whose torrents collide on id keep the first and report the second"
    (let [disk (mock-disk/create
                {:torrent-data {"/one/movie.torrent" {:info-hash (byte-array 20)
                                                      :info {:pieces ["h1"]}}
                                "/two/movie.torrent" {:info-hash (byte-array 20)
                                                      :info {:pieces ["h1"]}}}})
          net (mock-net/create)
          started (session/start-torrents (session/session (manager net disk))
                                          ["/one/movie.torrent" "/two/movie.torrent"]
                                          "/out")]
      (is (= ["movie"] (map :id (session/downloads started)))
          "the id keys the state file, so a second torrent cannot reuse it")
      (is (= ["/one/movie.torrent"] (map :torrent-path (session/downloads started))))
      (is (= [{:torrent-path "/two/movie.torrent" :error :duplicate-id}]
             (map #(select-keys % [:torrent-path :error]) (session/failures started)))))))

(deftest start-torrents-refuses-overlapping-output-test
  (testing "two torrents declaring the same output path cannot overwrite each other's bytes"
    (let [disk (mock-disk/create
                {:torrent-data {"/a.torrent" {:info-hash (byte-array 20)
                                              :info {:pieces ["h1"]
                                                     :piece-length 4
                                                     :name "payload.bin"
                                                     :length 4}}
                                "/b.torrent" {:info-hash (byte-array 20)
                                              :info {:pieces ["h1"]
                                                     :piece-length 4
                                                     :name "payload.bin"
                                                     :length 4}}}})
          net (mock-net/create)
          started (session/start-torrents (session/session (manager net disk))
                                          ["/a.torrent" "/b.torrent"]
                                          "/out")]
      (is (= ["a"] (map :id (session/downloads started)))
          "different ids still share one physical file")
      (is (= [{:torrent-path "/b.torrent" :error :overlapping-output}]
             (map #(select-keys % [:torrent-path :error]) (session/failures started)))))))

(deftest start-torrents-keeps-a-failed-announce-record-test
  (testing "a tracker refusal is a failed download, not a refused start"
    (let [disk (mock-disk/create
                {:torrent-data {"/x.torrent" {:info-hash (byte-array 20)
                                              :info {:pieces ["h1"]
                                                     :piece-length 4
                                                     :name "x.bin"
                                                     :length 4}}}})
          net (mock-net/create
               {:announce-to-url-error {:error :tracker-unreachable
                                        :message "down"}})
          started (session/start-torrents (session/session (manager net disk))
                                          ["/x.torrent"]
                                          "/out")
          record (first (session/downloads started))]
      (is (= [] (session/failures started)))
      (is (= ["x"] (map :id (session/downloads started)))
          "the failed record carries an id, so it can be saved and resumed")
      (is (= :failed (:state record)) "the refused announce leaves a failed record"))))

(deftest fdef-specs-hold-generatively-test
  ;; start-torrents is excluded from the check on principle: its
  ;; generator cannot conjure the live network/disk ports it drives, so a
  ;; check dies in the port call before reaching :ret.
  (testing "every check-grade session fdef holds over generated inputs"
    (is (= {}
           (test-utils/check-fdefs
            '[dev.cljtoc.orchestration.session/session
              dev.cljtoc.orchestration.session/downloads
              dev.cljtoc.orchestration.session/failures]
            50)))))
