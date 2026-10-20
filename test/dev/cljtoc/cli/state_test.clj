(ns dev.cljtoc.cli.state-test
  "Tests for CLI download state persistence.
   Seam: save-state / load-state through real temp dirs
   (no mocks — file system is the seam). ID derivation lives in
   dev.cljtoc.ports.disk/id-from-path, pinned generatively in disk-test."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [dev.cljtoc.cli.state :as state]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.test-utils :as test-utils]))

(defn- temp-disk-port
  "A real disk port over a fresh temp state dir. File system is the seam."
  [state-dir]
  (disk-impl/create {:state-dir state-dir
                     :piece-cache-dir (test-utils/temp-dir "cli-state-recent-cache-")}))

(defn- saved-record
  "A record shaped like the ones the client persists: progress reads piece
   state and stats, so a fixture without them is not a download."
  [id state]
  {:id id
   :state state
   :piece-state {:total-pieces 1 :needed #{} :in-flight #{} :verified #{}}
   :stats {:bytes-downloaded 0}})

(deftest load-most-recent-test
  (testing "returns the most recent record, read through the port"
    (let [dir (test-utils/temp-dir "cli-state-recent-")
          port (temp-disk-port dir)]
      (disk/save-state port (saved-record "old" :paused))
      (disk/save-state port (saved-record "new" :downloading))
      (.setLastModified (io/file dir "old.edn") 1000)
      (.setLastModified (io/file dir "new.edn") 2000)
      (is (= "new" (:id (state/load-most-recent port dir))))))
  (testing "a corrupt most-recent file reads as nil without falling through"
    (let [dir (test-utils/temp-dir "cli-state-recent-")
          port (temp-disk-port dir)]
      (disk/save-state port (saved-record "old" :paused))
      (spit (io/file dir "new.edn") "{broken edn")
      (.setLastModified (io/file dir "old.edn") 1000)
      (.setLastModified (io/file dir "new.edn") 2000)
      (is (nil? (state/load-most-recent port dir)))))
  (testing "an empty dir reads as nil"
    (let [dir (test-utils/temp-dir "cli-state-recent-")
          port (temp-disk-port dir)]
      (is (nil? (state/load-most-recent port dir))))))

(deftest load-record-rejects-a-record-filed-under-another-id-test
  (testing "a record whose :id differs from the requested id is not that download"
    (let [dir (test-utils/temp-dir "cli-state-id-")
          port (temp-disk-port dir)]
      (spit (io/file dir "requested.edn") (pr-str (saved-record "other" :paused)))
      (is (nil? (state/load-record port "requested"))
          "a named lookup must not show a record filed under another id")
      (is (= [] (state/load-all port dir))
          "the listing agrees: a mismatched file is not a download"))))

(deftest fdef-specs-hold-generatively-test
  ;; load-record is excluded from the check on principle: it reads through
  ;; the injected disk port, so the generator cannot conjure the port it
  ;; runs against.
  (testing "the pure, total record predicate holds over generated values"
    (is (= {}
           (test-utils/check-fdefs
            '[dev.cljtoc.cli.state/download-record?]
            50)))))

;; load-all carries an fdef but is excluded from the generative check on
;; principle: it reads through the injected disk port, so the generator
;; cannot conjure the port it runs against.
(deftest load-all-test
  (testing "returns every record, most recently modified first"
    (let [dir (test-utils/temp-dir "cli-state-all-")
          port (temp-disk-port dir)]
      (disk/save-state port (saved-record "old" :paused))
      (disk/save-state port (saved-record "new" :downloading))
      (.setLastModified (io/file dir "old.edn") 1000)
      (.setLastModified (io/file dir "new.edn") 2000)
      (is (= ["new" "old"] (map :id (state/load-all port dir))))))
  (testing "a corrupt record is skipped, not fatal: the rest stay listed"
    (let [dir (test-utils/temp-dir "cli-state-all-")
          port (temp-disk-port dir)]
      (disk/save-state port (saved-record "ok" :paused))
      (spit (io/file dir "broken.edn") "{broken edn")
      (.setLastModified (io/file dir "broken.edn") 2000)
      (.setLastModified (io/file dir "ok.edn") 1000)
      (is (= ["ok"] (map :id (state/load-all port dir))))))
  (testing "a missing dir reads as empty"
    (let [dir (test-utils/temp-dir "cli-state-all-")
          port (temp-disk-port dir)]
      (is (= [] (state/load-all port (str dir "/absent"))))))
  (testing "valid EDN of the wrong shape is corrupt too, not listed"
    (let [dir (test-utils/temp-dir "cli-state-all-")
          port (temp-disk-port dir)]
      (disk/save-state port (saved-record "ok" :paused))
      (spit (io/file dir "empty.edn") "{}")
      (spit (io/file dir "number.edn") "42")
      (spit (io/file dir "half.edn") "{:id \"half\" :state :paused}")
      (doseq [name ["empty.edn" "number.edn" "half.edn"]]
        (.setLastModified (io/file dir name) 3000))
      (.setLastModified (io/file dir "ok.edn") 1000)
      (is (= ["ok"] (map :id (state/load-all port dir)))
          "a wrong-shape record would throw in progress and hide the rest"))))

(deftest load-most-recent-ignores-a-wrong-shape-record-test
  (testing "a newest file that decodes to a non-record reads as nil, like a syntax error"
    (let [dir (test-utils/temp-dir "cli-state-recent-")
          port (temp-disk-port dir)]
      (disk/save-state port (saved-record "old" :paused))
      (spit (io/file dir "empty.edn") "{}")
      (.setLastModified (io/file dir "old.edn") 1000)
      (.setLastModified (io/file dir "empty.edn") 2000)
      (is (nil? (state/load-most-recent port dir))))))

