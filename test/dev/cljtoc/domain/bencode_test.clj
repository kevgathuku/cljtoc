(ns dev.cljtoc.domain.bencode-test
  (:require [clojure.test :refer :all]
            [dev.cljtoc.domain.bencode :as bencode]))

;; ---------------------------------------------------------------------------
;; GROUP 1: Error constructors, SHA-1, byte utilities
;; ---------------------------------------------------------------------------

(deftest bencode-error-test
  (testing "bencode-error returns properly structured error map"
    (let [err (bencode/bencode-error "unexpected byte" 5)]
      (is (= :bencode-parse-error (:error err)))
      (is (= "unexpected byte" (:message err)))
      (is (= 5 (:position err))))))

(deftest torrent-error-test
  (testing "torrent-error returns properly structured error map"
    (let [err (bencode/torrent-error "missing info dict" {:keys-found ["announce"]})]
      (is (= :invalid-torrent (:error err)))
      (is (= "missing info dict" (:message err)))
      (is (= {:keys-found ["announce"]} (:context err))))))

(deftest sha1-hash-test
  (testing "SHA-1 of empty input"
    (let [result (bencode/sha1-hash (byte-array 0))]
      (is (= 20 (count result)))
      (is (= "da39a3ee5e6b4b0d3255bfef95601890afd80709"
             (bencode/bytes->hex-string result)))))
  (testing "SHA-1 of 'hello'"
    (let [result (bencode/sha1-hash (.getBytes "hello" "UTF-8"))]
      (is (= 20 (count result)))
      (is (= "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
             (bencode/bytes->hex-string result))))))

(deftest bytes->hex-string-test
  (testing "converts byte arrays to lowercase hex"
    (is (= "00ff" (bencode/bytes->hex-string (byte-array [(byte 0) (byte -1)]))))
    (is (= "" (bencode/bytes->hex-string (byte-array 0))))
    (is (= "deadbeef"
           (bencode/bytes->hex-string
            (byte-array [(unchecked-byte 0xde) (unchecked-byte 0xad)
                         (unchecked-byte 0xbe) (unchecked-byte 0xef)]))))))
