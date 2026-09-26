(ns dev.cljtoc.cli.state-test
  "Tests for CLI download state persistence.
   Seam: save-state / load-state / id-from-path / get-or-create-download-id
   through real temp dirs (no mocks — file system is the seam)."
  (:require [clojure.test :refer :all]
            [dev.cljtoc.cli.state :as state]
            [clojure.java.io :as io]))

(defn- temp-dir []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "cli-state-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(deftest id-from-path-test
  (testing "derives a human-readable id from the torrent filename"
    (is (= "ubuntu-24.04" (state/id-from-path "/downloads/ubuntu-24.04.torrent")))
    (is (= "my-torrent" (state/id-from-path "my-torrent.torrent")))
    (is (= "no-extension" (state/id-from-path "/tmp/no-extension")))))

(deftest save-load-round-trip-test
  (testing "save then load returns the same id and state"
    (let [dir (temp-dir)
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
  (testing "byte arrays survive as deterministic hex strings (EDN-safe)"
    (let [dir (temp-dir)
          info-hash (byte-array [0 1 15 16 127 -1])
          download {:id "bytes-torrent"
                    :torrent {:info-hash info-hash}
                    :state :paused}]
      (state/save-state download dir)
      (let [loaded (state/load-state "bytes-torrent" dir)]
        (is (= "00010f107fff" (get-in loaded [:torrent :info-hash])))))))

(deftest load-missing-returns-nil-test
  (testing "loading an unknown id returns nil (resume can detect fresh start)"
    (let [dir (temp-dir)]
      (is (nil? (state/load-state "does-not-exist" dir))))))

(deftest get-or-create-download-id-test
  (testing "returns the human-readable id for a fresh torrent"
    (let [dir (temp-dir)]
      (is (= "fresh" (state/get-or-create-download-id "/dl/fresh.torrent" dir)))))
  (testing "returns the same id when state already exists (resume path)"
    (let [dir (temp-dir)]
      (state/save-state {:id "existing" :state :paused} dir)
      (is (= "existing" (state/get-or-create-download-id "/dl/existing.torrent" dir))))))
