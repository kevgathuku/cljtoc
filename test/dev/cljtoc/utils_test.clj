(ns dev.cljtoc.utils-test
  "Shared byte helpers: equality and test-data generation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dev.cljtoc.test-utils :as test-utils]
            [dev.cljtoc.utils :as utils]))

(deftest bytes-equal-test
  (testing "identical bytes are equal"
    (is (true? (utils/bytes-equal? (byte-array [1 2 3]) (byte-array [1 2 3])))))
  (testing "different bytes are not equal"
    (is (false? (utils/bytes-equal? (byte-array [1 2 3]) (byte-array [1 2 4])))))
  (testing "different lengths are not equal"
    (is (false? (utils/bytes-equal? (byte-array [1 2 3]) (byte-array [1 2])))))
  (testing "empty arrays are equal"
    (is (true? (utils/bytes-equal? (byte-array 0) (byte-array 0))))))

(deftest gen-byte-array-test
  (testing "samples come back at the requested length"
    (let [samples (gen/sample (utils/gen-byte-array 20) 10)]
      (is (every? #(= 20 (count %)) samples)))))

(deftest fdef-specs-hold-generatively-test
  (testing "bytes-equal? fdef holds over generated inputs"
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.utils/bytes-equal?]
                    50)]
      (is (empty? failures)
          (str "fdef check failures: " (pr-str failures))))))

;; ============================================================================
;; Byte Utility Tests
;; ============================================================================

(deftest int32-roundtrip-test
  (testing "int32-to-bytes and bytes-to-int32 are inverse operations"
    (let [test-values [0 1 -1 127 -128 255 256
                       2147483647 -2147483648
                       0x12345678 0x7FFFFFFF]]
      (doseq [val test-values]
        (let [encoded (utils/int32-to-bytes val)
              decoded (utils/bytes-to-int32 encoded)]
          (is (= val decoded)
              (format "Round-trip failed for %d" val))
          (is (= 4 (count encoded))
              (format "Encoded %d should be 4 bytes" val)))))))

(deftest int32-bigendian-test
  (testing "int32 encoding is big-endian"
    (let [encoded (utils/int32-to-bytes 0x12345678)]
      (is (= 0x12 (bit-and (aget encoded 0) 0xFF)))
      (is (= 0x34 (bit-and (aget encoded 1) 0xFF)))
      (is (= 0x56 (bit-and (aget encoded 2) 0xFF)))
      (is (= 0x78 (bit-and (aget encoded 3) 0xFF))))))

(deftest int16-roundtrip-test
  (testing "int16-to-bytes and bytes-to-int16 are inverse operations"
    (let [test-values [0 1 127 -128
                       32767 -32768 1000 -1000]]
      (doseq [val test-values]
        (let [encoded (utils/int16-to-bytes val)
              decoded (utils/bytes-to-int16 encoded)]
          (is (= val decoded)
              (format "Round-trip failed for %d" val))
          (is (= 2 (count encoded))
              (format "Encoded %d should be 2 bytes" val)))))))

(deftest bytes-to-int32-offset-test
  (testing "bytes-to-int32 respects offset parameter"
    (let [data (test-utils/make-bytes 0x00 0x00 0x12 0x34 0x56 0x78)]
      (is (= 0x1234 (utils/bytes-to-int16 data 2)))
      (is (= 0x12345678 (utils/bytes-to-int32 data 2))))))

(deftest concat-bytes-test
  (testing "concat-bytes combines byte arrays"
    (let [a (test-utils/make-bytes 0x01 0x02)
          b (test-utils/make-bytes 0x03 0x04)
          c (test-utils/make-bytes 0x05)
          result (utils/concat-bytes a b c)]
      (is (= 5 (count result)))
      (is (= 0x01 (bit-and (aget result 0) 0xFF)))
      (is (= 0x02 (bit-and (aget result 1) 0xFF)))
      (is (= 0x03 (bit-and (aget result 2) 0xFF)))
      (is (= 0x04 (bit-and (aget result 3) 0xFF)))
      (is (= 0x05 (bit-and (aget result 4) 0xFF))))))

;; ============================================================================
;; Generative Tests for Byte Utilities
;; ============================================================================

(defspec int32-roundtrip-generative 100
  (prop/for-all [val (gen/choose -2147483648 2147483647)]
                (let [encoded (utils/int32-to-bytes val)
                      decoded (utils/bytes-to-int32 encoded)]
                  (and (= val decoded)
                       (= 4 (count encoded))))))

(defspec int16-roundtrip-generative 100
  (prop/for-all [val (gen/choose -32768 32767)]
                (let [encoded (utils/int16-to-bytes val)
                      decoded (utils/bytes-to-int16 encoded)]
                  (and (= val decoded)
                       (= 2 (count encoded))))))
