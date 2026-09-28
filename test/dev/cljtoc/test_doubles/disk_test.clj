(ns dev.cljtoc.test-doubles.disk-test
  "Tests for MockDiskPort state persistence.
   Seam: IDiskPort save-state / load-state through the mock.
   The mock must round-trip through disk/encode-state and decode-state —
   the same seam DiskPortImpl persists through — so a resume-bytes
   regression fails here instead of only in production (issue #48)."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.test-doubles.disk :as mock-disk]))

(deftest mock-save-load-round-trips-bytes-test
  (testing "byte arrays saved through the mock load back as byte arrays"
    (let [port (mock-disk/create)
          info-hash (byte-array [0 1 15 16 127 -1])
          download {:id "bytes-torrent"
                    :torrent {:info-hash info-hash}
                    :state :paused}]
      (is (= {:ok :saved} (disk/save-state port download)))
      (let [result (disk/load-state port "bytes-torrent")
            loaded-hash (get-in result [:ok :torrent :info-hash])]
        (is (contains? result :ok))
        (is (= "bytes-torrent" (get-in result [:ok :id])))
        (is (bytes? loaded-hash))
        (is (java.util.Arrays/equals info-hash loaded-hash))))))

(deftest mock-persists-the-encoded-form-test
  (testing "the mock stores what the real port writes: the EDN-safe encoded form"
    (let [port (mock-disk/create)
          download {:id "bytes-torrent"
                    :torrent {:info-hash (byte-array [0 1 15 16 127 -1])}
                    :state :paused}]
      (disk/save-state port download)
      (is (= {:cljtoc/bytes "00010f107fff"}
             (get-in (mock-disk/get-state port "bytes-torrent")
                     [:torrent :info-hash]))))))

(deftest mock-load-missing-returns-nil-ok-test
  (testing "loading an unknown id returns {:ok nil}, like the real port"
    (let [port (mock-disk/create)]
      (is (= {:ok nil} (disk/load-state port "nope"))))))
