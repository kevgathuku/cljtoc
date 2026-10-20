(ns dev.cljtoc.orchestration.session-test
  "Tests for a session owning several downloads in one process.

   Seam: session start/run through mock ports, so two torrents run with
   no network and no real disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
          run (session/run-downloads started)
          record (first (session/downloads run))]
      (is (= [] (session/failures started)))
      (is (= ["x"] (map :id (session/downloads started)))
          "the failed record carries an id, so it can be saved and resumed")
      (is (= :failed (:state record)) "run-downloads leaves a refused announce untouched"))))

(deftest run-downloads-reports-each-result-as-it-finishes-test
  (testing "a finished download reaches the persister while its sibling is still blocked"
    (let [piece-0 (test-utils/to-bytes "abcd")
          info-hash-a (bencode/sha1-hash (test-utils/to-bytes "info-a"))
          torrent-a {:info-hash info-hash-a
                     :announce "http://a.test/announce"
                     :info {:pieces [(bencode/sha1-hash piece-0)]
                            :piece-length 4
                            :name "a.bin"
                            :length 4}}
          torrent-b {:info-hash (bencode/sha1-hash (test-utils/to-bytes "info-b"))
                     :announce "http://b.test/announce"
                     :info {:pieces [(bencode/sha1-hash piece-0)]
                            :piece-length 4
                            :name "b.bin"
                            :length 4}}
          release-b (promise)
          a-saved (promise)
          disk (mock-disk/create
                {:torrent-data {"/a.torrent" torrent-a
                                "/b.torrent" torrent-b}})
          net (mock-net/create
               {:mock-peers []
                :announce-to-url-responses {"http://a.test/announce"
                                            {:ok #{"10.0.0.1:6881"}}
                                            "http://b.test/announce"
                                            {:ok #{"10.0.0.2:6881"}}}
                :connect-response
                (fn [address]
                  (if (str/starts-with? address "10.0.0.2")
                    (do
                      (deref release-b 5000 false)
                      {:error :connect-failed :message "blocked"})
                    {:ok {:id address
                          :address address
                          :bitfield #{0}}}))
                :handshake-response {:ok {:info-hash info-hash-a
                                          :peer-id (byte-array 20)}}
                :receive-responses (atom [{:ok (peer/->Bitfield
                                                (byte-array [(unchecked-byte 0x80)]))}
                                          {:ok (peer/->Unchoke)}
                                          {:ok (peer/->Piece 0 0 piece-0)}])})
          runner (Thread.
                  (fn []
                    (session/run-downloads
                     (session/start-torrents (session/session (manager net disk))
                                             ["/a.torrent" "/b.torrent"]
                                             "/out")
                     (fn [download]
                       (when (= "a" (:id download))
                         (deliver a-saved true))))))]
      (.start runner)
      (is (true? (deref a-saved 5000 false))
          "A's terminal record reached the persister before B finished")
      (is (.isAlive runner) "B is still blocked, so the session has not returned")
      (deliver release-b true)
      (.join runner 10000))))

(deftest run-downloads-records-a-persister-failure-test
  (testing "an on-complete exception is recorded on its download, not raised to the caller"
    (let [disk (mock-disk/create
                {:torrent-data {"/x.torrent" {:info-hash (byte-array 20)
                                              :announce "http://x.test/announce"
                                              :info {:pieces ["h1"]
                                                     :piece-length 4
                                                     :name "x.bin"
                                                     :length 4}}}})
          net (mock-net/create
               {:mock-peers []
                :announce-to-url-responses {"http://x.test/announce"
                                            {:ok #{"10.0.0.1:6881"}}}
                :connect-response {:error :connect-failed :message "down"}})
          run (session/run-downloads
               (session/start-torrents (session/session (manager net disk))
                                       ["/x.torrent"] "/out")
               (fn [_] (throw (ex-info "persister exploded" {}))))
          record (first (session/downloads run))]
      (is (= :on-complete-exception (get-in record [:session/persist-error :reason])))
      (is (= :failed (:state record))
          "the download's own outcome survives a failing persister")))
  (testing "a persister that returns an error envelope is surfaced on the record"
    (let [disk (mock-disk/create
                {:torrent-data {"/x.torrent" {:info-hash (byte-array 20)
                                              :announce "http://x.test/announce"
                                              :info {:pieces ["h1"]
                                                     :piece-length 4
                                                     :name "x.bin"
                                                     :length 4}}}})
          net (mock-net/create
               {:mock-peers []
                :announce-to-url-responses {"http://x.test/announce"
                                            {:ok #{"10.0.0.1:6881"}}}
                :connect-response {:error :connect-failed :message "down"}})
          run (session/run-downloads
               (session/start-torrents (session/session (manager net disk))
                                       ["/x.torrent"] "/out")
               (fn [_] {:reason :persist-error :message "disk full"}))
          record (first (session/downloads run))]
      (is (= :persist-error (get-in record [:session/persist-error :reason])))
      (is (= "disk full" (get-in record [:session/persist-error :message]))))))

(deftest run-one-folds-an-unexpected-throw-test
  (testing "a throwing run becomes a failed record instead of taking the session down"
    (let [torrent {:info-hash (byte-array 20)
                   :info {:pieces [] :piece-length 4 :name "x.bin" :length 0}}
          download {:id "x" :torrent torrent :state :downloading :peers #{}}
          result (deref (#'session/run-one {:disk-port nil :config {}} download (fn [_])))]
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
