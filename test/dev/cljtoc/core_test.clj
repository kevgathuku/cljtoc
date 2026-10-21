(ns dev.cljtoc.core-test
  "Tests for the CLI command glue.

   The commands are thin RPC clients now: each sends one request and
   prints the answer, so the wiring worth pinning is the
   settings-to-ports construction and the flag parsing below."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.core :as core]
            [dev.cljtoc.cli.config :as config]
            [dev.cljtoc.test-utils :as test-utils]))

(deftest make-ports-builds-one-manager-from-the-shared-dirs-test
  (testing "both commands resume against the same piece cache the download
            wrote, so the construction lives in one place. The ports are
            real implementations; creating them touches nothing. Explicit
            settings flow through untouched, so tests never touch home dirs"
    (let [settings (assoc (config/default-settings nil)
                          :state-dir (test-utils/temp-dir "cfg-state-")
                          :piece-cache-dir (test-utils/temp-dir "cfg-cache-")
                          :peer-port 9999
                          :max-peers 7)
          {:keys [manager time-port]} (#'core/make-ports settings)]
      (is (some? (:network-port manager)))
      (is (some? (:disk-port manager)))
      (is (some? (:time-port manager)))
      (is (some? time-port))
      (is (= (:state-dir settings) (str (:state-dir (:disk-port manager))))
          "the state resume loads is the state download saved")
      (is (= (:piece-cache-dir settings) (str (:piece-cache-dir (:disk-port manager))))
          "the cache resume materializes from is the cache download wrote")
      (is (= 9999 (get-in manager [:network-port :config :peer-port]))
          "the configured announce port reaches the network port")
      (is (= 7 (get-in manager [:config :max-peers]))
          "manager knobs travel from settings to config"))))

(deftest split-global-flags-test
  (testing "--config <path> is pulled out wherever it sits"
    (is (= ["/tmp/c.edn" ["torrent.status"]]
           (#'core/split-global-flags ["--config" "/tmp/c.edn" "torrent.status"])))
    (is (= ["/tmp/c.edn" ["torrent.download" "f.torrent"]]
           (#'core/split-global-flags ["torrent.download" "f.torrent" "--config" "/tmp/c.edn"]))))
  (testing "no flag means nil config and untouched args"
    (is (= [nil ["torrent.status" "x"]]
           (#'core/split-global-flags ["torrent.status" "x"]))))
  (testing "a dangling --config keeps its token so -main rejects it before loading"
    (is (= [nil ["torrent.status" "--config"]]
           (#'core/split-global-flags ["torrent.status" "--config"])))
    (is (#'core/dangling-config-flag? ["torrent.status" "--config"]))))

(deftest try-load-settings-returns-nil-on-a-bad-file-test
  (testing "a corrupt config yields nil after reporting, leaving exit to the caller"
    (let [dir (test-utils/temp-dir "cfg-")
          path (str dir "/bad.edn")]
      (spit path "{:max-peers")
      (is (nil? (#'core/try-load-settings path)))))
  (testing "an explicit path that does not exist yields nil after reporting"
    (is (nil? (#'core/try-load-settings
               (str (test-utils/temp-dir "cfg-") "/absent.edn")))))
  (testing "a valid file yields its merged settings"
    (let [dir (test-utils/temp-dir "cfg-")
          path (str dir "/ok.edn")]
      (spit path (str "{:max-peers 3 :output-dir \"" dir "/out\"}"))
      (let [settings (#'core/try-load-settings path)]
        (is (= 3 (:max-peers settings)))
        (is (= (str dir "/out") (:output-dir settings)))))))

;; ---------------------------------------------------------------------------
;; Coverage residue for this namespace, recorded rather than papered over:
;; the command bodies (cmd-torrent-start/pause/resume/status/stop),
;; print-usage, and -main's dispatch are not covered. Each calls System/exit
;; on its failure path, and the repo forbids with-redefs, so driving one
;; from a test would kill the test JVM when the wiring regressed — a
;; failure mode worse than no test. The wiring they call is covered at its
;; own seam instead: make-ports (settings reach the ports and the manager),
;; and the daemon's handlers plus the RPC transport (the start/status/pause/
;; resume/stop answers the commands print), which is where a regression
;; would actually land.
;; ---------------------------------------------------------------------------

(deftest dangling-config-flag-is-detected-test
  (testing "a --config with no value is flagged before anything loads or runs"
    (is (#'core/dangling-config-flag? ["torrent.stop" "abc" "--config"]))
    (is (not (#'core/dangling-config-flag? ["torrent.status"]))))
  (testing "split-global-flags strips --config and its value, so the predicate sees the residue"
    (let [[config-path rest-args] (#'core/split-global-flags
                                   ["--config" "/tmp/c.edn" "torrent.status"])]
      (is (= "/tmp/c.edn" config-path))
      (is (not (#'core/dangling-config-flag? rest-args))))))

;; ---------------------------------------------------------------------------
;; Coverage residue for the `dangling-config-flag?` branch in -main:
;; the lines printing usage and calling System/exit are untestable
;; without with-redefs (repo forbids), so coverage of this branch is
;; structurally partial. (See `coverage_diff.sh` for the script that
;; surfaces them as uncovered lines in a diff run.)
;; ---------------------------------------------------------------------------
