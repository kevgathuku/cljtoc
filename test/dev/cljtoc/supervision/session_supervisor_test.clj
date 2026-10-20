(ns dev.cljtoc.supervision.session-supervisor-test
  "Tests for the session supervisor that owns one download subtree per
   torrent.

   Seam: the session runs through mock ports, so two torrents run with
   no network and no real disk; the child seam drives the session's own
   restart path."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.orchestration.session :as session]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.supervision.session-supervisor :as session-supervisor]
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

(defn- started-session
  "The session value for two torrents, started against the mock ports."
  [network-port disk-port]
  (-> (session/session (manager network-port disk-port))
      (session/start-torrents ["/a.torrent" "/b.torrent"] "/out")))

(deftest one-download-restart-leaves-its-siblings-untouched-test
  (testing "a restarted download never touches the download running beside it"
    (let [a-calls (atom 0)
          b-calls (atom 0)
          child (fn [download-record]
                  (if (= "a" (:id download-record))
                    (do (swap! a-calls inc)
                        (if (< @a-calls 3)
                          (throw (ex-info "download supervisor crash" {}))
                          {:id "a" :state :completed}))
                    (do (swap! b-calls inc)
                        {:id "b" :state :completed})))
          supervisor (session-supervisor/start (manager (mock-net/create)
                                                        (mock-disk/create))
                                               [{:id "a"} {:id "b"}]
                                               {:child child})]
      (session-supervisor/await-all supervisor)
      (let [records (session-supervisor/records supervisor)]
        (is (= 3 @a-calls) "the crashed download ran again")
        (is (= 1 @b-calls) "its sibling ran once, untouched")
        (is (= ["a" "b"] (map :id records)))
        (is (= [:completed :completed] (map :state records)))))))

(deftest a-child-that-keeps-crashing-fails-alone-test
  (testing "a child that crashes past its budget fails without taking the session down"
    (let [calls (atom 0)
          child (fn [download-record]
                  (if (= "a" (:id download-record))
                    (do (swap! calls inc)
                        (throw (ex-info "download supervisor crash" {})))
                    {:id "b" :state :completed}))
          supervisor (session-supervisor/start (manager (mock-net/create)
                                                        (mock-disk/create))
                                               [{:id "a"} {:id "b"}]
                                               {:budget {:limit 2 :window-ms 60000}
                                                :child child})]
      (session-supervisor/await-all supervisor)
      (let [records (into {} (map (juxt :id identity))
                          (session-supervisor/records supervisor))]
        (is (= 3 @calls) "the initial attempt plus the two restarts the budget allowed")
        (is (= :failed (get-in records ["a" :state])))
        (is (= :restart-budget-exhausted (get-in records ["a" :error :reason])))
        (is (= :completed (get-in records ["b" :state]))
            "the sibling finished while its neighbour failed")))))

(deftest session-supervisor-drives-every-download-and-isolates-failures-test
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
          started (started-session net disk)
          supervisor (session-supervisor/start (manager net disk)
                                               (session/downloads started)
                                               {})
          _ (session-supervisor/await-all supervisor)
          by-id (into {} (map (juxt :id identity))
                      (session-supervisor/records supervisor))]
      (is (= ["a" "b"] (sort (keys by-id))))
      (is (= :completed (:state (get by-id "a"))))
      (is (= :failed (:state (get by-id "b"))))
      (is (= :no-peers (get-in by-id ["b" :error :reason]))))))

(deftest session-supervisor-runs-downloads-concurrently-test
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
          started (started-session net disk)
          supervisor (session-supervisor/start (manager net disk)
                                               (session/downloads started)
                                               {})]
      (session-supervisor/await-all supervisor)
      (is (= 2 (count (session-supervisor/records supervisor))))
      (is (= 2 @peak) "both dials were in flight together"))))

(deftest session-supervisor-reports-each-result-as-it-finishes-test
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
          started (started-session net disk)
          supervisor (session-supervisor/start (manager net disk)
                                               (session/downloads started)
                                               {:on-complete
                                                (fn [download]
                                                  (when (= "a" (:id download))
                                                    (deliver a-saved true)))})]
      (is (true? (deref a-saved 5000 false))
          "A's terminal record reached the persister before B finished")
      (is (= 1 (count (session-supervisor/records supervisor)))
          "B is still blocked, so the session has not returned")
      (deliver release-b true)
      (session-supervisor/await-all supervisor))))

(deftest session-supervisor-records-a-persister-failure-test
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
          started (session/start-torrents (session/session (manager net disk))
                                          ["/x.torrent"] "/out")
          supervisor (session-supervisor/start (manager net disk)
                                               (session/downloads started)
                                               {:on-complete
                                                (fn [_] (throw (ex-info "persister exploded" {})))})]
      (session-supervisor/await-all supervisor)
      (let [record (first (session-supervisor/records supervisor))]
        (is (= :on-complete-exception (get-in record [:session/persist-error :reason])))
        (is (= :failed (:state record))
            "the download's own outcome survives a failing persister"))))
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
          started (session/start-torrents (session/session (manager net disk))
                                          ["/x.torrent"] "/out")
          supervisor (session-supervisor/start (manager net disk)
                                               (session/downloads started)
                                               {:on-complete
                                                (fn [_] {:reason :persist-error
                                                         :message "disk full"})})]
      (session-supervisor/await-all supervisor)
      (let [record (first (session-supervisor/records supervisor))]
        (is (= :persist-error (get-in record [:session/persist-error :reason])))
        (is (= "disk full" (get-in record [:session/persist-error :message])))))))

(deftest session-supervisor-lists-only-finished-downloads-test
  (testing "a download still running has no record yet"
    (let [release (promise)
          child (fn [download-record]
                  (deref release 5000 false)
                  {:id (:id download-record) :state :completed})
          supervisor (session-supervisor/start (manager (mock-net/create)
                                                        (mock-disk/create))
                                               [{:id "a"}]
                                               {:child child})]
      (is (= [] (session-supervisor/records supervisor)))
      (deliver release true)
      (session-supervisor/await-all supervisor)
      (is (= ["a"] (map :id (session-supervisor/records supervisor)))))))
