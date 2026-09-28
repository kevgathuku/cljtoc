(ns dev.cljtoc.cli.state-test
  "Tests for CLI download state persistence.
   Seam: save-state / load-state / id-from-path / get-or-create-download-id
   through real temp dirs (no mocks — file system is the seam)."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.cli.state :as state]
            [dev.cljtoc.test-utils :as test-utils]))

(deftest id-from-path-test
  (testing "derives a human-readable id from the torrent filename"
    (is (= "ubuntu-24.04" (state/id-from-path "/downloads/ubuntu-24.04.torrent")))
    (is (= "my-torrent" (state/id-from-path "my-torrent.torrent")))
    (is (= "no-extension" (state/id-from-path "/tmp/no-extension")))))

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

(deftest get-or-create-download-id-test
  (testing "returns the human-readable id for a fresh torrent"
    (let [dir (test-utils/temp-dir "cli-state-test-")]
      (is (= "fresh" (state/get-or-create-download-id "/dl/fresh.torrent" dir)))))
  (testing "returns the same id when state already exists (resume path)"
    (let [dir (test-utils/temp-dir "cli-state-test-")]
      (state/save-state {:id "existing" :state :paused} dir)
      (is (= "existing" (state/get-or-create-download-id "/dl/existing.torrent" dir))))))
