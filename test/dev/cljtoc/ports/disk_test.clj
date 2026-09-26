(ns dev.cljtoc.ports.disk-test
  "Spec and property tests for the single persistence seam.
   Seam: ports.disk/encode-state, decode-state, id-from-path (pure fns)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.spec.test.alpha :as stest]
            [clojure.edn :as edn]
            [dev.cljtoc.ports.disk :as disk]))

(def bytes-gen
  "Generator for arbitrary byte arrays."
  (gen/fmap (fn [ints] (byte-array (map unchecked-byte ints)))
            (gen/vector (gen/choose -128 127) 0 64)))

(defspec bytes-round-trip-generative 100
  (prop/for-all [original bytes-gen]
    (let [restored (get (disk/decode-state
                         (edn/read-string
                          (pr-str (disk/encode-state {:hash original}))))
                        :hash)]
      (and (bytes? restored)
           (java.util.Arrays/equals ^bytes original ^bytes restored)))))

(defspec nested-bytes-round-trip-generative 100
  (prop/for-all [first-bytes bytes-gen
                 second-bytes bytes-gen]
    (let [download {:id "x"
                    :torrent {:info-hash first-bytes
                              :info {:pieces [second-bytes]}}}
          restored (disk/decode-state
                    (edn/read-string (pr-str (disk/encode-state download))))]
      (and (java.util.Arrays/equals ^bytes first-bytes
                                    ^bytes (get-in restored [:torrent :info-hash]))
           (java.util.Arrays/equals ^bytes second-bytes
                                    ^bytes (first (get-in restored [:torrent :info :pieces])))))))

(defspec id-from-path-round-trip-generative 100
  (prop/for-all [file-name (gen/such-that (comp not empty?) gen/string-alphanumeric)]
    (= file-name (disk/id-from-path (str "/dl/" file-name ".torrent")))))

(deftest id-from-path-fdef-check-test
  (testing "id-from-path conforms to fdef spec"
    (let [check-result (stest/check 'dev.cljtoc.ports.disk/id-from-path
                                    {:clojure.spec.test.check/opts {:num-tests 50}})]
      (is (nil? (-> check-result first :failure))
          "Function should pass all generative tests"))))
