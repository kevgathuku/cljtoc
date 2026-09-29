(ns dev.cljtoc.utils-test
  "Shared byte helpers: equality and test-data generation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
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
