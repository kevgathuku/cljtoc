(ns dev.cljtoc.daemon-test
  "Tests for the daemon: the RPC handlers over a live session, and the
   daemon's own stop."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [dev.cljtoc.daemon :as daemon]
            [dev.cljtoc.cli.state :as cli-state]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.daemon.records :as records]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.rpc :as rpc]
            [dev.cljtoc.test-doubles.network :as mock-net]
            [dev.cljtoc.test-doubles.disk :as mock-disk]
            [dev.cljtoc.test-doubles.time :as mock-time]
            [dev.cljtoc.test-utils :as test-utils])
  (:import [java.net ServerSocket]))

(defn- free-port
  "A port nothing is listening on."
  []
  (let [socket (ServerSocket. 0)]
    (try (.getLocalPort socket)
         (finally (.close socket)))))

(defn- daemon-fixture
  "A manager over the mock network and a real disk in fresh temp dirs, so
   the daemon's state loading and torrent parsing are real, plus the
   settings the daemon reads. Everything the daemon needs comes from one
   value."
  ([prefix]
   (daemon-fixture prefix (mock-net/create) nil))
  ([prefix net]
   (daemon-fixture prefix net nil))
  ([prefix net disk]
   (let [state-dir (test-utils/temp-dir (str prefix "-state-"))
         cache-dir (test-utils/temp-dir (str prefix "-cache-"))
         output-dir (test-utils/temp-dir (str prefix "-output-"))]
     {:manager (download/manager net
                                 (or disk
                                     (disk-impl/create {:state-dir state-dir
                                                        :piece-cache-dir cache-dir}))
                                 (mock-time/create))
      :settings {:rpc-port (free-port)
                 :state-dir state-dir
                 :output-dir output-dir}})))

(defn- torrent-file
  "Write a one-piece torrent to a temp dir and return its path."
  [prefix]
  (let [dir (test-utils/temp-dir (str prefix "-torrent-"))
        piece (test-utils/to-bytes "abcd")
        info {"name" "daemon.bin"
              "piece length" 4
              "pieces" (bencode/sha1-hash piece)
              "length" 4}
        metadata {"announce" "http://127.0.0.1:6969/announce"
                  "info" info}
        path (str dir "/daemon.torrent")
        out-bytes (bencode/encode-bencode metadata)]
    (io/make-parents path)
    (with-open [out (io/output-stream path)]
      (.write ^java.io.OutputStream out ^bytes out-bytes))
    path))

(defn- wait-for-records
  "True once the daemon's status reports at least one record."
  [daemon-instance]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (let [response (rpc/request (:rpc-port daemon-instance) :status {})]
        (cond
          (seq (get-in response [:ok :records])) true
          (> (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 20) (recur)))))))

(deftest the-daemon-answers-status-with-the-live-records-test
  (testing "the RPC's status reads the daemon's live view, running downloads included"
    (let [{:keys [manager settings]} (daemon-fixture "status")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (is (= {:ok {:records []}} (rpc/request (:rpc-port daemon-instance)
                                                :status {})))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-starts-a-torrent-and-returns-test
  (testing "a start request starts the download and returns while it runs"
    (let [{:keys [manager settings]} (daemon-fixture "start")
          daemon-instance (daemon/start settings {:manager manager})
          path (torrent-file "start")]
      (try
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :start {:paths [path]})]
          (is (nil? (:error response)) (pr-str response))
          (is (= 1 (count (get-in response [:ok :downloads])))))
        ;; The download is running in the daemon, so status sees it —
        ;; with the id and path the status command prints, not the raw
        ;; record, whose byte arrays do not survive the wire.
        (is (wait-for-records daemon-instance))
        (let [status (rpc/request (:rpc-port daemon-instance) :status {})
              [record] (get-in status [:ok :records])]
          (is (= "daemon" (:id record)))
          (is (string? (:torrent-path record)))
          (is (number? (:percent record))))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-pauses-a-running-download-test
  (testing "pause marks the record paused and saves it, the way the old CLI did"
    (let [{:keys [manager settings]} (daemon-fixture "pause")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (records/put (:records daemon-instance)
                     (assoc (test-utils/saved-record "p1" :downloading)
                            :peers #{}))
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :pause {:id "p1"})
              record (get-in response [:ok :record])]
          (is (nil? (:error response)) (pr-str response))
          (is (= :paused (:state record)))
          (is (= "p1" (:id record)))
          (is (= :paused (:state (cli-state/load-record
                                  (:disk-port manager) "p1")))
              "the paused record reached the disk, so a crash keeps the pause"))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-status-filters-by-id-test
  (testing "a named id lists only that download; an unknown one lists nothing"
    (let [{:keys [manager settings]} (daemon-fixture "status-id")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (records/put (:records daemon-instance)
                     (test-utils/saved-record "one" :paused))
        (records/put (:records daemon-instance)
                     (test-utils/saved-record "two" :paused))
        (is (= ["one"] (map :id (get-in (rpc/request (:rpc-port daemon-instance)
                                                     :status {:id "one"})
                                        [:ok :records]))))
        (is (= [] (get-in (rpc/request (:rpc-port daemon-instance)
                                       :status {:id "ghost"})
                          [:ok :records])))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-refuses-to-pause-a-record-that-is-not-running-test
  (testing "pausing a paused record fails the request instead of stamping it twice"
    (let [{:keys [manager settings]} (daemon-fixture "pause-idle")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (records/put (:records daemon-instance)
                     (test-utils/saved-record "p2" :paused))
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :pause {:id "p2"})]
          (is (= :not-running (:error response))))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-reports-a-pause-it-cannot-save-test
  (testing "a pause the disk refuses fails the request instead of lying idle"
    (let [{:keys [manager settings]} (daemon-fixture "pause-save")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        ;; The junk tag survives the pause transition and makes the
        ;; state write throw, exactly like a hostile pre-existing file.
        (records/put (:records daemon-instance)
                     (assoc (test-utils/saved-record "p3" :downloading)
                            :peers #{}
                            :junk {:cljtoc/bytes "zz"}))
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :pause {:id "p3"})]
          (is (= :save-error (:error response))))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-refuses-to-resume-an-unknown-download-test
  (testing "resuming an id the daemon never saw is refused"
    (let [{:keys [manager settings]} (daemon-fixture "resume-ghost")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :resume {:id "ghost"})]
          (is (= :unknown-download (:error response))))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-stops-a-known-download-test
  (testing "stop idles the record, forgets it, and deletes its state file"
    (let [{:keys [manager settings]} (daemon-fixture "stop-known")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (records/put (:records daemon-instance)
                     (test-utils/saved-record "s1" :paused))
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :stop {:id "s1"})
              record (get-in response [:ok :record])]
          (is (nil? (:error response)) (pr-str response))
          (is (= :idle (:state record)))
          (is (nil? (records/record-for (:records daemon-instance) "s1"))
              "the daemon no longer owns the download")
          (is (nil? (cli-state/load-record (:disk-port manager) "s1"))
              "its state file is gone too"))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-reports-a-stop-it-cannot-delete-test
  (testing "a stop whose state file cannot be deleted fails the request"
    (let [{:keys [manager settings]}
          (daemon-fixture "stop-delete"
                          (mock-net/create)
                          (mock-disk/create
                           {:delete-error {:error :delete-error
                                           :message "boom"}}))
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (records/put (:records daemon-instance)
                     (test-utils/saved-record "s2" :paused))
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :stop {:id "s2"})]
          (is (= :delete-error (:error response)))
          (is (= "boom" (:message response))))
        (finally
          ((:stop daemon-instance)))))))

(deftest records-fdefs-hold-generatively-test
  (testing "the records view fdefs are check-grade"
    (is (empty? (test-utils/check-fdefs
                 '[dev.cljtoc.daemon.records/create
                   dev.cljtoc.daemon.records/record-for
                   dev.cljtoc.daemon.records/put
                   dev.cljtoc.daemon.records/forget
                   dev.cljtoc.daemon.records/all]
                 50))
        "a failing drawer names its own counterexample")))

(deftest the-daemon-refuses-a-record-it-cannot-resume-test
  (testing "a completed record is refused before any swarm work"
    (let [{:keys [manager settings]} (daemon-fixture "refuse")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (records/put (:records daemon-instance)
                     (test-utils/saved-record "done" :completed))
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :resume {:id "done"})]
          (is (= :not-paused (:error response)))
          (is (nil? (get-in @(:session-supervisor daemon-instance)
                            [:children "done"]))
              "a refusal starts nothing"))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-finalizes-a-resume-the-cache-completes-test
  (testing "a resume the cache already completes never contacts the tracker"
    (let [piece-0 (test-utils/to-bytes "abcd")
          piece-1 (test-utils/to-bytes "efgh")
          info-hash (bencode/sha1-hash (test-utils/to-bytes "cached-info"))
          torrent {:info-hash info-hash
                   :info {:pieces [(bencode/sha1-hash piece-0)
                                   (bencode/sha1-hash piece-1)]
                          :piece-length 4
                          :name "cached.bin"
                          :length 8}}
          captured (atom nil)
          {:keys [manager settings]} (daemon-fixture
                                      "cached"
                                      (mock-net/create {:announce-capture captured}))
          disk (:disk-port manager)]
      (disk/write-piece disk info-hash 0 piece-0)
      (disk/write-piece disk info-hash 1 piece-1)
      (let [daemon-instance (daemon/start settings {:manager manager})]
        (try
          (records/put
           (:records daemon-instance)
           (assoc (test-utils/saved-record "cached" :paused)
                  :torrent torrent
                  :output-dir (test-utils/temp-dir "cached-output-")
                  :peers #{}
                  :piece-state {:total-pieces 2 :needed #{} :in-flight #{}
                                :verified #{0 1}}))
          (let [response (rpc/request (:rpc-port daemon-instance)
                                      :resume {:id "cached"})
                record (get-in response [:ok :record])]
            (is (nil? (:error response)) (pr-str response))
            (is (= :completed (:state record)))
            (is (some? (:completed-at (:stats (records/record-for
                                               (:records daemon-instance)
                                               "cached"))))
                "the fast path finalizes stats exactly like a swarmed completion")
            (is (nil? @captured) "neither announce nor swarm was touched")
            (is (nil? (get-in @(:session-supervisor daemon-instance)
                              [:children "cached"]))
                "a completed resume starts no run"))
          (finally
            ((:stop daemon-instance))))))))

(deftest the-daemon-rejoins-an-incomplete-resume-test
  (testing "a resume that still needs pieces rejoins the live session"
    (let [{:keys [manager settings]} (daemon-fixture "rejoin")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (records/put
         (:records daemon-instance)
         (assoc (test-utils/saved-record "rejoin" :paused)
                :torrent {:info-hash (bencode/sha1-hash
                                      (test-utils/to-bytes "rejoin-info"))
                          :info {:pieces [(bencode/sha1-hash
                                           (test-utils/to-bytes "abcd"))]
                                 :piece-length 4
                                 :name "rejoin.bin"
                                 :length 4}}
                :output-dir (test-utils/temp-dir "rejoin-output-")
                :peers #{}))
        (let [response (rpc/request (:rpc-port daemon-instance)
                                    :resume {:id "rejoin"})
              record (get-in response [:ok :record])]
          (is (nil? (:error response)) (pr-str response))
          (is (= :downloading (:state record)))
          (is (some? (get-in @(:session-supervisor daemon-instance)
                             [:children "rejoin"]))
              "the revived download runs under the session supervisor"))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-refuses-an-unknown-download-test
  (testing "pause and stop on an id the daemon does not own are refused"
    (let [{:keys [manager settings]} (daemon-fixture "unknown")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (is (= :unknown-download
               (:error (rpc/request (:rpc-port daemon-instance)
                                    :pause {:id "ghost"}))))
        (is (= :unknown-download
               (:error (rpc/request (:rpc-port daemon-instance)
                                    :stop {:id "ghost"}))))
        (finally
          ((:stop daemon-instance)))))))

(deftest the-daemon-answers-an-unknown-command-test
  (testing "a command the daemon does not know is refused"
    (let [{:keys [manager settings]} (daemon-fixture "command")
          daemon-instance (daemon/start settings {:manager manager})]
      (try
        (is (= :unknown-command
               (:error (rpc/request (:rpc-port daemon-instance)
                                    :nonsense {}))))
        (finally
          ((:stop daemon-instance)))))))

(deftest stopping-the-daemon-ends-the-rpc-test
  (testing "the daemon's stop closes the RPC port"
    (let [{:keys [manager settings]} (daemon-fixture "stop")
          daemon-instance (daemon/start settings {:manager manager})]
      ((:stop daemon-instance))
      (is (= rpc/no-daemon
             (rpc/request (:rpc-port daemon-instance) :status {}))))))
