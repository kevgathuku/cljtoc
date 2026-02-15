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

;; ---------------------------------------------------------------------------
;; GROUP 2: Decode strings and integers
;; ---------------------------------------------------------------------------

(defn- to-bytes [^String s]
  (.getBytes s "UTF-8"))

(deftest decode-string-test
  (testing "decodes basic string"
    (let [[val pos] (bencode/decode-string (to-bytes "4:spam") 0)]
      (is (= "spam" (String. ^bytes val "UTF-8")))
      (is (= 6 pos))))
  (testing "decodes empty string"
    (let [[val pos] (bencode/decode-string (to-bytes "0:") 0)]
      (is (= "" (String. ^bytes val "UTF-8")))
      (is (= 2 pos))))
  (testing "decodes at non-zero offset"
    (let [bs (to-bytes "xx3:foo")
          [val pos] (bencode/decode-string bs 2)]
      (is (= "foo" (String. ^bytes val "UTF-8")))
      (is (= 7 pos))))
  (testing "truncated input returns error"
    (let [result (bencode/decode-string (to-bytes "4:sp") 0)]
      (is (map? result))
      (is (= :bencode-parse-error (:error result)))))
  (testing "missing colon returns error"
    (let [result (bencode/decode-string (to-bytes "4spam") 0)]
      (is (map? result))
      (is (= :bencode-parse-error (:error result)))))
  (testing "no digits returns error"
    (let [result (bencode/decode-string (to-bytes ":spam") 0)]
      (is (map? result))
      (is (= :bencode-parse-error (:error result))))))

(deftest decode-integer-test
  (testing "decodes positive integer"
    (let [[val pos] (bencode/decode-integer (to-bytes "i42e") 0)]
      (is (= 42 val))
      (is (= 4 pos))))
  (testing "decodes zero"
    (let [[val pos] (bencode/decode-integer (to-bytes "i0e") 0)]
      (is (= 0 val))
      (is (= 3 pos))))
  (testing "decodes negative integer"
    (let [[val pos] (bencode/decode-integer (to-bytes "i-3e") 0)]
      (is (= -3 val))
      (is (= 4 pos))))
  (testing "leading zeros return error"
    (let [result (bencode/decode-integer (to-bytes "i03e") 0)]
      (is (map? result))
      (is (= :bencode-parse-error (:error result)))))
  (testing "negative zero returns error"
    (let [result (bencode/decode-integer (to-bytes "i-0e") 0)]
      (is (map? result))
      (is (= :bencode-parse-error (:error result)))))
  (testing "missing e returns error"
    (let [result (bencode/decode-integer (to-bytes "i42") 0)]
      (is (map? result))
      (is (= :bencode-parse-error (:error result)))))
  (testing "empty ie returns error"
    (let [result (bencode/decode-integer (to-bytes "ie") 0)]
      (is (map? result))
      (is (= :bencode-parse-error (:error result)))))
  (testing "decodes at non-zero offset"
    (let [[val pos] (bencode/decode-integer (to-bytes "xxi99e") 2)]
      (is (= 99 val))
      (is (= 6 pos)))))

;; ---------------------------------------------------------------------------
;; GROUP 3: Decode lists, dicts, nested structures & public API
;; ---------------------------------------------------------------------------

(deftest decode-bencode-list-test
  (testing "decodes list of integers"
    (let [result (bencode/decode-bencode (to-bytes "li1ei2ei3ee"))]
      (is (= {:ok [1 2 3]} result))))
  (testing "decodes empty list"
    (let [result (bencode/decode-bencode (to-bytes "le"))]
      (is (= {:ok []} result))))
  (testing "decodes list with mixed types"
    (let [result (bencode/decode-bencode (to-bytes "l4:spami42ee"))]
      (is (= {:ok ["spam" 42]} result))))
  (testing "truncated list returns error"
    (let [result (bencode/decode-bencode (to-bytes "li1ei2e"))]
      (is (= :bencode-parse-error (:error result))))))

(deftest decode-bencode-dict-test
  (testing "decodes simple dict"
    (let [result (bencode/decode-bencode (to-bytes "d3:bar4:spam3:fooi42ee"))]
      (is (= {:ok (sorted-map "bar" "spam" "foo" 42)} result))))
  (testing "decodes empty dict"
    (let [result (bencode/decode-bencode (to-bytes "de"))]
      (is (= {:ok (sorted-map)} result))))
  (testing "dict keys are strings"
    (let [{:keys [ok]} (bencode/decode-bencode (to-bytes "d1:ai1e1:bi2ee"))]
      (is (= ["a" "b"] (keys ok)))))
  (testing "truncated dict returns error"
    (let [result (bencode/decode-bencode (to-bytes "d3:foo"))]
      (is (= :bencode-parse-error (:error result))))))

(deftest decode-bencode-nested-test
  (testing "dict in list"
    (let [result (bencode/decode-bencode (to-bytes "ld1:ai1eee"))]
      (is (= {:ok [(sorted-map "a" 1)]} result))))
  (testing "list in dict"
    (let [result (bencode/decode-bencode (to-bytes "d1:ali1ei2eee"))]
      (is (= {:ok (sorted-map "a" [1 2])} result))))
  (testing "deeply nested"
    (let [result (bencode/decode-bencode (to-bytes "d1:ad1:bd1:ci1eeee"))]
      (is (= {:ok (sorted-map "a" (sorted-map "b" (sorted-map "c" 1)))} result)))))

(deftest decode-bencode-errors-test
  (testing "empty input returns error"
    (let [result (bencode/decode-bencode (byte-array 0))]
      (is (= :bencode-parse-error (:error result)))))
  (testing "unknown type byte returns error"
    (let [result (bencode/decode-bencode (to-bytes "x"))]
      (is (= :bencode-parse-error (:error result)))))
  (testing "trailing data after value returns error"
    (let [result (bencode/decode-bencode (to-bytes "i42eXXX"))]
      (is (= :bencode-parse-error (:error result))))))
