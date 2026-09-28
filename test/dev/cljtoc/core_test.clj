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
