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

(deftest load-most-recent-test
  (testing "returns the most recent record, read through the port"
    (let [dir (test-utils/temp-dir "cli-state-recent-")
          port (temp-disk-port dir)]
      (disk/save-state port {:id "old" :state :paused})
      (disk/save-state port {:id "new" :state :downloading})
      (.setLastModified (io/file dir "old.edn") 1000)
      (.setLastModified (io/file dir "new.edn") 2000)
      (is (= "new" (:id (state/load-most-recent port dir))))))
  (testing "a corrupt most-recent file reads as nil without falling through"
    (let [dir (test-utils/temp-dir "cli-state-recent-")
          port (temp-disk-port dir)]
      (disk/save-state port {:id "old" :state :paused})
      (spit (io/file dir "new.edn") "{broken edn")
      (.setLastModified (io/file dir "old.edn") 1000)
      (.setLastModified (io/file dir "new.edn") 2000)
      (is (nil? (state/load-most-recent port dir)))))
  (testing "an empty dir reads as nil"
    (let [dir (test-utils/temp-dir "cli-state-recent-")
          port (temp-disk-port dir)]
      (is (nil? (state/load-most-recent port dir))))))

