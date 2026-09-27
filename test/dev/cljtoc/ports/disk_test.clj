(ns dev.cljtoc.ports.disk-test
  "Spec and property tests for the single persistence seam.
   Seam: ports.disk/encode-state, decode-state, id-from-path (pure fns)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.spec.test.alpha :as stest]
            [clojure.edn :as edn]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.test-utils :as test-utils]))

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

(deftest valid-output-layout-fdef-check-test
  (testing "valid-output-layout? conforms to fdef spec over any input"
    (let [check-result (stest/check 'dev.cljtoc.ports.disk/valid-output-layout?
                                    {:clojure.spec.test.check/opts {:num-tests 50}})]
      (is (nil? (-> check-result first :failure))
          "Function should pass all generative tests"))))

(deftest consistent-output-layout-test
  (testing "compiled shapes pass"
    (is (true? (disk/consistent-output-layout?
                {:files [{:path ["t" "a"] :length 4 :start 0}]
                 :sizes {["t" "a"] 4} :total 4 :piece-length 4})))
    (is (true? (disk/consistent-output-layout?
                {:files [] :sizes {["e"] 0} :total 0 :piece-length 4})))))

(def layout-mutations
  "One-field breaks of a valid compiled layout; every one must fail the
   predicate. Each targets a different clause: entry shapes, sizes
   shapes, files-in-sizes membership, total agreement, start chaining,
   and the O(1) gate fields."
  [(fn [layout] (update-in layout [:files 0 :path] conj 42))
   (fn [layout] (assoc-in layout [:files 0 :path] []))
   (fn [layout] (assoc-in layout [:files 0 :path] nil))
   (fn [layout] (assoc-in layout [:files 0 :length] -1))
   (fn [layout] (assoc-in layout [:files 0 :length] "x"))
   (fn [layout] (assoc-in layout [:files 0 :start] -1))
   (fn [layout] (update-in layout [:files 0 :start] inc))
   (fn [layout] (assoc layout :files [42]))
   (fn [layout] (update layout :sizes dissoc (first (keys (:sizes layout)))))
   (fn [layout] (update layout :total inc))
   (fn [layout] (assoc layout :piece-length 0))
   (fn [layout] (assoc layout :piece-length nil))
   (fn [layout] (assoc layout :piece-length "x"))
   (fn [layout] (assoc-in layout [:sizes (first (keys (:sizes layout)))] "x"))
   (fn [layout] (assoc layout :sizes {42 4}))
   (fn [layout] (assoc layout :sizes {[] 4}))
   (fn [layout] (assoc-in layout [:sizes (first (keys (:sizes layout)))] 999))
   (fn [layout] (-> layout
                    (update :sizes assoc ["t" "extra"] (:total layout))
                    (update :total + (:total layout))))
   (fn [layout] (assoc layout :files 42))
   (fn [layout] (if (< 1 (count (:files layout)))
                  (update-in layout [:files 1 :start] inc)
                  (update-in layout [:files 0 :start] inc)))
   (fn [layout] (assoc layout :sizes
                       {(conj (first (keys (:sizes layout))) 42) 4}))])

(defspec compiled-layouts-are-consistent-spec 100
  (prop/for-all [piece-length (gen/choose 1 16)
                 file-lengths (gen/vector (gen/choose 1 20) 1 5)]
                (true? (disk/consistent-output-layout?
                        (test-utils/compiled-test-layout piece-length file-lengths)))))

(defspec inconsistent-mutations-are-rejected-spec 100
  (prop/for-all [piece-length (gen/choose 1 16)
                 file-lengths (gen/vector (gen/choose 1 20) 1 5)
                 mutation-idx (gen/choose 0 (dec (count layout-mutations)))]
                (false? (disk/consistent-output-layout?
                         ((nth layout-mutations mutation-idx)
                          (test-utils/compiled-test-layout piece-length file-lengths))))))

(deftest consistent-output-layout-fdef-check-test
  (testing "consistent-output-layout? conforms to fdef spec over any input"
    (let [check-result (stest/check 'dev.cljtoc.ports.disk/consistent-output-layout?
                                    {:clojure.spec.test.check/opts {:num-tests 50}})]
      (is (nil? (-> check-result first :failure))
          "Function should pass all generative tests"))))
