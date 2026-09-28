(ns dev.cljtoc.cli.state-test
  "Tests for CLI download state persistence.
   Seam: save-state / load-state through real temp dirs
   (no mocks — file system is the seam). ID derivation lives in
   dev.cljtoc.ports.disk/id-from-path, pinned generatively in disk-test."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.cli.state :as state]
            [dev.cljtoc.test-utils :as test-utils]))

(deftest save-load-round-trip-test
  (testing "save then load returns the same id and state"
    (let [dir (test-utils/temp-dir "cli-state-test-")
          download {:id "my-torrent"
                    :torrent-path "/downloads/my-torrent.torrent"
                    :output-dir "/output"
                    :state :paused
                    :started-at 12345
                    :error nil}]
      (state/save-state download dir)
      (let [loaded (state/load-state "my-torrent" dir)]
        (is (= "my-torrent" (:id loaded)))
        (is (= :paused (:state loaded)))
        (is (= "/downloads/my-torrent.torrent" (:torrent-path loaded)))))))

(deftest save-load-with-byte-arrays-test
  (testing "byte arrays round-trip as bytes through the CLI seam (EDN-safe on disk)"
    (let [dir (test-utils/temp-dir "cli-state-test-")
          info-hash (byte-array [0 1 15 16 127 -1])
          download {:id "bytes-torrent"
                    :torrent {:info-hash info-hash}
                    :state :paused}]
      (state/save-state download dir)
      (let [loaded-hash (get-in (state/load-state "bytes-torrent" dir)
                                [:torrent :info-hash])]
        (is (bytes? loaded-hash))
        (is (java.util.Arrays/equals info-hash loaded-hash))))))

(deftest load-missing-returns-nil-test
  (testing "loading an unknown id returns nil (resume can detect fresh start)"
    (let [dir (test-utils/temp-dir "cli-state-test-")]
      (is (nil? (state/load-state "does-not-exist" dir))))))

