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

(deftest id-from-path-falls-back-to-filename-for-dotfiles-test
  (testing "stripping the extension must never yield an unusable id"
    (is (= ".torrent" (disk/id-from-path "/dl/.torrent")))
    (is (= "..torrent" (disk/id-from-path "/dl/..torrent")))
    (is (= "ubuntu" (disk/id-from-path "/dl/ubuntu.torrent")))
    (is (= "README" (disk/id-from-path "/dl/README")))))

(def hex-char-gen
  "Generator for single lowercase hex chars."
  (gen/elements [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \a \b \c \d \e \f]))

(def valid-hex-gen
  "Generator for even-length hex strings: valid tag content by
   construction (paired chars can never come out odd-length)."
  (gen/fmap (fn [pairs] (apply str (mapcat (fn [[first-char second-char]]
                                             [first-char second-char])
                                           pairs)))
            (gen/vector (gen/tuple hex-char-gen hex-char-gen) 0 16)))

(def hostile-tag-value-gen
  "Generator for tag values that are invalid by construction, each class
   violating a different clause of the contract independent of how the
   implementation checks it: a \"zz\" suffix can never match the hex
   alphabet; forcing odd length can never satisfy evenness; non-strings
   can never satisfy stringness."
  (gen/one-of [(gen/fmap #(str % "zz") gen/string-alphanumeric)
               (gen/fmap (fn [text] (if (even? (count text)) (str text "0") text))
                         gen/string-alphanumeric)
               gen/int
               (gen/return nil)
               (gen/vector gen/int 0 4)
               (gen/fmap #(into {} %) (gen/vector (gen/tuple gen/keyword gen/int) 0 3))]))

(defn- encode-throws?
  "True when encoding download throws (fail-closed on hostile input)."
  [download]
  (try
    (disk/encode-state download)
    false
    (catch clojure.lang.ExceptionInfo _ true)))

(defspec hostile-tag-values-are-rejected-spec 100
  (prop/for-all [bad-value hostile-tag-value-gen]
                (encode-throws? {:id "h" :hash {:cljtoc/bytes bad-value}})))

(defspec valid-tags-pass-through-spec 100
  (prop/for-all [hex valid-hex-gen]
                (= {:cljtoc/bytes hex}
                   (:hash (disk/encode-state {:id "h" :hash {:cljtoc/bytes hex}})))))

(defspec encode-is-idempotent-spec 100
  (prop/for-all [original bytes-gen]
                (let [once (disk/encode-state {:hash original})]
                  (= once (disk/encode-state once)))))

(deftest valid-tags-pass-through-test
  (testing "a pre-existing valid tag is already encoded: stored untouched"
    (is (= {:id "t" :hash {:cljtoc/bytes "00ff"}}
           (disk/encode-state {:id "t" :hash {:cljtoc/bytes "00ff"}})))
    (is (= {:id "t" :hash {:cljtoc/bytes "00FF"}}
           (disk/encode-state {:id "t" :hash {:cljtoc/bytes "00FF"}}))))
  (testing "a map merely carrying the key among others is plain data,
            not the tag shape decode restores: stored untouched"
    (is (= {:id "t" :hash {:cljtoc/bytes "zz" :other 1}}
           (disk/encode-state {:id "t" :hash {:cljtoc/bytes "zz" :other 1}})))))

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

(defn- prepared-for
  "A prepared value for layout as prepare-output-layout would freeze it:
   one resolved entry per declared path and an empty parent-mtimes map.
   The filesystem strings are arbitrary — validity is shape, not
   reachability."
  [layout]
  {:layout layout
   :output-dir "/tmp/out"
   :parent-mtimes {}
   :resolved (into {}
                   (map (fn [declared-path]
                          [declared-path {:file (str "/tmp/out/" (last declared-path))
                                          :canonical (str "/tmp/out/" (last declared-path))
                                          :identity nil}]))
                   (keys (:sizes layout)))})

(deftest valid-prepared-layout-test
  (testing "the frozen shape passes, non-prepared values fail"
    (let [layout (test-utils/compiled-test-layout 4 [4 4])]
      (is (true? (disk/valid-prepared-layout? (prepared-for layout))))
      (is (false? (disk/valid-prepared-layout? nil)))
      (is (false? (disk/valid-prepared-layout? "prepared")))
      (is (false? (disk/valid-prepared-layout? {:layout layout})))
      (is (false? (disk/valid-prepared-layout?
                   (assoc (prepared-for layout) :output-dir 42))))
      (is (false? (disk/valid-prepared-layout?
                   (update (prepared-for layout) :resolved
                           dissoc (first (keys (:sizes layout)))))))
      (is (false? (disk/valid-prepared-layout?
                   (assoc-in (prepared-for layout)
                             [:resolved (first (keys (:sizes layout))) :file] 42))))
      (is (false? (disk/valid-prepared-layout?
                   (assoc-in (prepared-for layout)
                             [:resolved (first (keys (:sizes layout))) :canonical] nil))))
      (is (false? (disk/valid-prepared-layout?
                   (update-in (prepared-for layout)
                              [:resolved (first (keys (:sizes layout)))]
                              dissoc :identity))))
      (is (false? (disk/valid-prepared-layout?
                   (assoc (prepared-for layout) :layout
                          (assoc layout :piece-length 0))))))))

(def prepared-mutations
  "One-field breaks of a valid prepared value; every one must fail the
   predicate. Each targets a different clause: the carried layout, the
   output dir, the parent-mtimes map, resolved membership, and entry shapes."
  [(fn [prepared] (assoc-in prepared [:layout :piece-length] 0))
   (fn [prepared] (assoc prepared :layout nil))
   (fn [prepared] (assoc prepared :output-dir 42))
   (fn [prepared] (assoc prepared :output-dir nil))
   (fn [prepared] (dissoc prepared :parent-mtimes))
   (fn [prepared] (assoc prepared :parent-mtimes "not-a-map"))
   (fn [prepared] (dissoc prepared :resolved))
   (fn [prepared] (update prepared :resolved
                          dissoc (first (keys (:resolved prepared)))))
   (fn [prepared] (update prepared :resolved assoc ["extra"]
                          {:file "/tmp/out/extra" :canonical "/tmp/out/extra"}))
   (fn [prepared] (assoc-in prepared [:resolved
                                      (first (keys (:resolved prepared)))
                                      :file]
                            42))
   (fn [prepared] (assoc-in prepared [:resolved
                                      (first (keys (:resolved prepared)))
                                      :canonical]
                            nil))
   (fn [prepared] (update-in prepared [:resolved
                                       (first (keys (:resolved prepared)))]
                             dissoc :identity))
   (fn [prepared] (assoc-in prepared [:resolved
                                      (first (keys (:resolved prepared)))]
                            "entry"))])

(defspec prepared-layouts-are-valid-spec 100
  (prop/for-all [piece-length (gen/choose 1 16)
                 file-lengths (gen/vector (gen/choose 1 20) 1 5)]
                (true? (disk/valid-prepared-layout?
                        (prepared-for
                         (test-utils/compiled-test-layout piece-length file-lengths))))))

(defspec broken-prepared-mutations-are-rejected-spec 100
  (prop/for-all [piece-length (gen/choose 1 16)
                 file-lengths (gen/vector (gen/choose 1 20) 1 5)
                 mutation-idx (gen/choose 0 (dec (count prepared-mutations)))]
                (false? (disk/valid-prepared-layout?
                         ((nth prepared-mutations mutation-idx)
                          (prepared-for
                           (test-utils/compiled-test-layout piece-length file-lengths)))))))

(deftest valid-prepared-layout-fdef-check-test
  (testing "valid-prepared-layout? conforms to fdef spec over any input"
    (let [check-result (stest/check 'dev.cljtoc.ports.disk/valid-prepared-layout?
                                    {:clojure.spec.test.check/opts {:num-tests 50}})]
      (is (nil? (-> check-result first :failure))
          "Function should pass all generative tests"))))

(deftest writable-prepared-test
  (testing "the per-piece gate needs only the touched entries"
    (let [layout (test-utils/compiled-test-layout 4 [4 4])
          prepared (prepared-for layout)
          touched (keys (:sizes layout))]
      (is (true? (disk/writable-prepared? prepared touched)))
      (is (true? (disk/writable-prepared? prepared [(first touched)])))
      (is (true? (disk/writable-prepared? prepared [])))
      (is (false? (disk/writable-prepared? nil touched)))
      (is (false? (disk/writable-prepared? "prepared" touched)))
      (is (false? (disk/writable-prepared?
                   (assoc prepared :layout nil) touched)))
      (is (false? (disk/writable-prepared?
                   (assoc prepared :layout (assoc layout :piece-length 0)) touched)))
      (is (false? (disk/writable-prepared?
                   (update prepared :resolved dissoc (first touched)) touched)))
      (is (false? (disk/writable-prepared?
                   (assoc-in prepared [:resolved (first touched) :canonical] nil)
                   touched)))
      (is (false? (disk/writable-prepared? prepared [["t" "missing"]]))))))

(deftest writable-prepared-fdef-check-test
  (testing "writable-prepared? conforms to fdef spec over any input"
    (let [check-result (stest/check 'dev.cljtoc.ports.disk/writable-prepared?
                                    {:clojure.spec.test.check/opts {:num-tests 50}})]
      (is (nil? (-> check-result first :failure))
          "Function should pass all generative tests"))))
