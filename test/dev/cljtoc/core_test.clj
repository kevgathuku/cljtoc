(ns dev.cljtoc.core-test
  "Tests for the CLI command glue.

   The commands are the only place that wires a persisted record to a live
   run, and a wiring bug there is invisible from the library: the download
   functions are all correct while the command quietly does nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.core :as core]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [clojure.java.io :as io]
            [dev.cljtoc.test-utils :as test-utils]
            [dev.cljtoc.test-doubles.network :as mock-net]
            [dev.cljtoc.test-doubles.disk :as mock-disk]
            [dev.cljtoc.test-doubles.time :as mock-time]))

(defn- mock-manager []
  {:network-port (mock-net/create)
   :disk-port (mock-disk/create)
   :time-port (mock-time/create)
   :config {}})

(defn- failed-record
  "A 2-piece download that failed with piece 0 verified."
  []
  (let [piece-0 (test-utils/to-bytes "abcd")
        piece-1 (test-utils/to-bytes "efgh")
        info {:pieces [(bencode/sha1-hash piece-0) (bencode/sha1-hash piece-1)]
              :piece-length 4
              :name "cli.bin"
              :length 8}
        torrent {:info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
                 :info info}]
    (assoc (download/initial-download (mock-time/create) torrent "/out" "cli")
           :state :failed
           :piece-state (-> (pieces/initial-piece-state 2)
                            (#(:ok (pieces/mark-in-flight % 0)))
                            (#(:ok (pieces/mark-verified % 0))))
           :peers #{{:address "10.0.0.9:6881" :port 6881}}
           :error {:reason :no-peers :message "All peers disconnected"})))

(deftest resume-and-run-refuses-a-record-it-cannot-resume-test
  (testing "a completed download is refused before any swarm work, so the
            refusal envelope reaches the caller instead of a run's result"
    (let [done (assoc (failed-record) :state :completed)
          result (#'core/resume-and-run (mock-manager) done)]
      (is (= :not-paused (:error result)))
      (is (nil? (:state result))))))

(deftest resume-and-run-restarts-the-loop-test
  (testing "resume-and-run hands the record to run-download rather than
            returning the resumed record. The mock swarm fails every dial,
            so a real run ends :failed :no-peers -- if this only resumed, the
            record would still read :downloading and the command would print
            progress over a download nothing is downloading"
    (let [result (#'core/resume-and-run (mock-manager) (failed-record))]
      (is (= :failed (:state result)))
      (is (= :no-peers (get-in result [:error :reason]))))))

(deftest resume-and-run-skips-the-swarm-when-the-cache-completes-test
  (testing "a resume the cache already completes never contacts the tracker
            or the swarm: reconcile marks it :completed and resume-and-run
            returns it instead of re-running"
    (let [piece-0 (test-utils/to-bytes "abcd")
          piece-1 (test-utils/to-bytes "efgh")
          info {:pieces [(bencode/sha1-hash piece-0)
                         (bencode/sha1-hash piece-1)]
                :piece-length 4
                :name "done.bin"
                :length 8}
          torrent {:info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
                   :info info}
          done (assoc (download/initial-download (mock-time/create)
                                                 torrent "/out" "done")
                      :state :paused
                      :peers #{}
                      :piece-state (-> (pieces/initial-piece-state 2)
                                       (#(:ok (pieces/mark-in-flight % 0)))
                                       (#(:ok (pieces/mark-verified % 0)))
                                       (#(:ok (pieces/mark-in-flight % 1)))
                                       (#(:ok (pieces/mark-verified % 1)))))
          disk (mock-disk/create)
          _ (disk/write-piece disk "done" 0 piece-0)
          _ (disk/write-piece disk "done" 1 piece-1)
          captured (atom nil)
          manager (assoc (mock-manager)
                         :disk-port disk
                         :network-port (mock-net/create {:announce-capture captured}))
          result (#'core/resume-and-run manager done)]
      (is (= :completed (:state result)))
      (is (some? (:completed-at (:stats result)))
          "the fast path finalizes stats exactly like a swarmed completion")
      (is (nil? @captured) "neither announce nor swarm was touched"))))

(deftest resume-and-run-excludes-the-dead-gap-from-the-rate-test
  (testing "a failed record resumed long after its last byte folds the dead
            gap into the downtime total, so the average below measures active
            time whether the gap was a pause, a failure, or a crash"
    (let [stale (assoc-in (failed-record) [:stats :last-update] 2000)
          manager (assoc (mock-manager)
                         :time-port (mock-time/create {:now 5000}))
          result (#'core/resume-and-run manager stale)]
      (is (= :failed (:state result))
          "the mock swarm still fails every dial")
      (is (= 3000 (:downtime-ms (:stats result))))
      (is (= 5000 (:last-update (:stats result)))))))

(deftest resume-and-run-materializes-cached-pieces-once-test
  (testing "an incomplete resume reconciles the cache before announcing and
            run-download does not repeat it: each cached piece reaches the
            output layout once across the whole resume-to-run flow"
    (let [piece-0 (test-utils/to-bytes "abcd")
          info {:pieces [(bencode/sha1-hash piece-0)
                         (bencode/sha1-hash (test-utils/to-bytes "efgh"))]
                :piece-length 4
                :name "once.bin"
                :length 8}
          torrent {:info-hash (bencode/sha1-hash (test-utils/to-bytes "fake-info"))
                   :info info}
          paused (assoc (download/initial-download (mock-time/create)
                                                   torrent "/out" "once")
                        :state :paused
                        :peers #{}
                        :piece-state (-> (pieces/initial-piece-state 2)
                                         (#(:ok (pieces/mark-in-flight % 0)))
                                         (#(:ok (pieces/mark-verified % 0)))))
          disk (mock-disk/create)
          _ (disk/write-piece disk "once" 0 piece-0)
          manager (assoc (mock-manager) :disk-port disk)
          result (#'core/resume-and-run manager paused)]
      (is (= :failed (:state result))
          "the mock swarm fails every dial, so the run still ends there")
      (is (= 1 (count (mock-disk/get-output-layouts disk)))
          "one output write per cached piece across resume and run"))))

(deftest refusal-distinguishes-envelopes-from-records-test
  (testing "resume-and-run returns either a refusal envelope (:error, no
            :state) or a Download record (always has :state). Checking
            :error alone mistook a :failed record for a refusal and dropped
            it without saving, so the refusal test must key on the missing
            :state"
    (is (#'core/refusal?
         {:error :not-paused
          :message "Download is not resumable from state :completed"}))
    (is (not (#'core/refusal? (failed-record)))
        "a :failed record carries :error too, but it must be saved, not refused")
    (is (not (#'core/refusal?
              (assoc (failed-record) :state :downloading :error nil))))))

(deftest make-ports-builds-one-manager-from-the-shared-dirs-test
  (testing "both commands resume against the same piece cache the download
            wrote, so the construction lives in one place. The ports are
            real implementations; creating them touches nothing"
    (let [{:keys [manager time-port]} (#'core/make-ports)]
      (is (some? (:network-port manager)))
      (is (some? (:disk-port manager)))
      (is (some? (:time-port manager)))
      (is (some? time-port))
      (is (= "./torrent-state" (str (:state-dir (:disk-port manager))))
          "the state resume loads is the state download saved")
      (is (= "./torrent-cache" (str (:piece-cache-dir (:disk-port manager))))
          "the cache resume materializes from is the cache download wrote"))))

(defn- temp-disk-port
  "A real disk port over a fresh temp state dir (plus a throwaway cache dir
   the port constructor requires). File system is the seam — no mocks."
  [state-dir]
  (disk-impl/create {:state-dir state-dir
                     :piece-cache-dir (test-utils/temp-dir "core-test-cache-")}))

(deftest load-command-state-test
  (testing "by id loads the record the port saved"
    (let [dir (test-utils/temp-dir "core-cmd-state-")
          port (temp-disk-port dir)]
      (is (= {:ok :saved} (disk/save-state port {:id "cmd-1" :state :paused})))
      (let [loaded (#'core/load-command-state port dir ["cmd-1"])]
        (is (= "cmd-1" (:id loaded)))
        (is (= :paused (:state loaded))))))
  (testing "an unknown id reads as nil, so commands print not-found"
    (let [dir (test-utils/temp-dir "core-cmd-state-")
          port (temp-disk-port dir)]
      (is (nil? (#'core/load-command-state port dir ["nope"])))))
  (testing "a corrupt file reads as nil — the CLI's missing-state story"
    (let [dir (test-utils/temp-dir "core-cmd-state-")
          port (temp-disk-port dir)]
      (spit (io/file dir "corrupt.edn") "{broken edn")
      (is (nil? (#'core/load-command-state port dir ["corrupt"])))))
  (testing "no id falls back to the most recent record"
    (let [dir (test-utils/temp-dir "core-cmd-state-")
          port (temp-disk-port dir)]
      (disk/save-state port {:id "old" :state :paused})
      (disk/save-state port {:id "new" :state :downloading})
      (.setLastModified (io/file dir "old.edn") 1000)
      (.setLastModified (io/file dir "new.edn") 2000)
      (is (= "new" (:id (#'core/load-command-state port dir []))))))
  (testing "no id with an empty dir reads as nil"
    (let [dir (test-utils/temp-dir "core-cmd-state-")
          port (temp-disk-port dir)]
      (is (nil? (#'core/load-command-state port dir []))))))
