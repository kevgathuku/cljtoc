(ns dev.cljtoc.orchestration.session-test
  "Tests for a session owning several downloads in one process.

   Seam: session start/run through mock ports, so two torrents run with
   no network and no real disk."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.orchestration.session :as session]
            [dev.cljtoc.protocol.peer :as peer]
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

(deftest run-downloads-drives-every-download-and-isolates-failures-test
  (testing "one torrent completes while its sibling fails, both kept in one session"
    (let [piece-0 (test-utils/to-bytes "abcd")
          piece-1 (test-utils/to-bytes "efgh")
          info-hash-a (bencode/sha1-hash (test-utils/to-bytes "info-a"))
          torrent-a {:info-hash info-hash-a
                     :announce "http://a.test/announce"
                     :info {:pieces [(bencode/sha1-hash piece-0)
                                     (bencode/sha1-hash piece-1)]
                            :piece-length 4
                            :name "a.bin"
                            :length 8}}
          torrent-b {:info-hash (bencode/sha1-hash (test-utils/to-bytes "info-b"))
                     :announce "http://b.test/announce"
                     :info {:pieces [(bencode/sha1-hash piece-0)
                                     (bencode/sha1-hash piece-1)]
                            :piece-length 4
                            :name "b.bin"
                            :length 8}}
          disk (mock-disk/create
                {:torrent-data {"/a.torrent" torrent-a
                                "/b.torrent" torrent-b}})
          net (mock-net/create
               {:mock-peers []
                :announce-to-url-responses {"http://a.test/announce"
                                            {:ok #{"10.0.0.9:6881"}}}
                :handshake-response {:ok {:info-hash info-hash-a
                                          :peer-id (byte-array 20)}}
                :receive-responses (atom [{:ok (peer/->Bitfield
                                                (byte-array [(unchecked-byte 0xC0)]))}
                                          {:ok (peer/->Unchoke)}
                                          {:ok (peer/->Piece 0 0 piece-0)}
                                          {:ok (peer/->Piece 1 0 piece-1)}])})
          session-value (-> (session/session (manager net disk))
                            (session/start-torrents ["/a.torrent" "/b.torrent"] "/out")
                            session/run-downloads)
          by-id (into {} (map (juxt :id identity) (session/downloads session-value)))]
      (is (= ["a" "b"] (sort (keys by-id))))
      (is (= :completed (:state (get by-id "a"))))
      (is (= :failed (:state (get by-id "b"))))
      (is (= :no-peers (get-in by-id ["b" :error :reason]))))))

(deftest run-downloads-runs-downloads-concurrently-test
  (testing "both downloads are in flight at the same time, not serialized"
    (let [active (atom 0)
          peak (atom 0)
          barrier (promise)
          net (mock-net/create
               {:mock-peers ["10.0.0.9:6881"]
                :connect-response
                (fn [_address]
                  (let [in-flight (swap! active inc)]
                    (swap! peak max in-flight)
                    (when (>= in-flight 2) (deliver barrier true))
                    (deref barrier 3000 false)
                    (swap! active dec)
                    {:error :connect-failed :message "barrier"}))})
          disk (mock-disk/create
                {:torrent-data {"/a.torrent" {:info-hash (bencode/sha1-hash
                                                          (test-utils/to-bytes "curl-a"))
                                              :info {:pieces ["h1" "h2"]
                                                     :piece-length 4
                                                     :name "a.bin"
                                                     :length 8}}
                                "/b.torrent" {:info-hash (bencode/sha1-hash
                                                          (test-utils/to-bytes "curl-b"))
                                              :info {:pieces ["h1" "h2"]
                                                     :piece-length 4
                                                     :name "b.bin"
                                                     :length 8}}}})
          session-value (-> (session/session (manager net disk))
                            (session/start-torrents ["/a.torrent" "/b.torrent"] "/out")
                            session/run-downloads)]
      (is (= 2 (count (session/downloads session-value))))
      (is (= 2 @peak) "both dials were in flight together"))))

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

(deftest run-one-folds-an-unexpected-throw-test
  (testing "a throwing run becomes a failed record instead of taking the session down"
    (let [torrent {:info-hash (byte-array 20)
                   :info {:pieces [] :piece-length 4 :name "x.bin" :length 0}}
          download {:id "x" :torrent torrent :state :downloading :peers #{}}
          result (deref (#'session/run-one {:disk-port nil :config {}} download))]
      (is (= :failed (:state result)))
      (is (= :run-exception (get-in result [:error :reason]))))))

(deftest fdef-specs-hold-generatively-test
  ;; start-torrents and run-downloads are excluded from the check on
  ;; principle: their generators cannot conjure the live network/disk ports
  ;; they drive, so a check dies in the port call before reaching :ret.
  (testing "every check-grade session fdef holds over generated inputs"
    (is (= {}
           (test-utils/check-fdefs
            '[dev.cljtoc.orchestration.session/session
              dev.cljtoc.orchestration.session/downloads
              dev.cljtoc.orchestration.session/failures]
            50)))))
