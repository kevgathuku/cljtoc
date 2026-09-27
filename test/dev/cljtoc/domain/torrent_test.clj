(ns dev.cljtoc.domain.torrent-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.test-utils :as test-utils]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-pieces
  "Generate n fake 20-byte piece hashes."
  [n]
  (byte-array (mapcat (fn [i] (repeat 20 (mod i 256))) (range n))))

(defn- make-single-file-torrent-bytes
  "Build a minimal single-file torrent as bencoded bytes."
  [& {:keys [announce name piece-length pieces length]
      :or {announce "http://tracker.example.com/announce"
           name "test.txt"
           piece-length 262144
           pieces (make-pieces 2)
           length 500000}}]
  (let [info {"length" length
              "name" name
              "piece length" piece-length
              "pieces" pieces}]
    (bencode/encode-bencode
     {"announce" announce
      "info" info})))

(defn- make-multi-file-torrent-bytes
  "Build a minimal multi-file torrent as bencoded bytes."
  []
  (let [info {"files" [{"length" 100 "path" ["dir" "file1.txt"]}
                       {"length" 200 "path" ["dir" "file2.txt"]}]
              "name" "my-torrent"
              "piece length" 262144
              "pieces" (make-pieces 1)}]
    (bencode/encode-bencode
     {"announce" "http://tracker.example.com/announce"
      "info" info})))

;; ---------------------------------------------------------------------------
;; GROUP 5: Info dict extraction, info hash, announce URLs
;; ---------------------------------------------------------------------------

(deftest extract-info-dict-bytes-test
  (testing "extracts info dict bytes matching standalone encoding"
    (let [info-map {"length" 500000
                    "name" "test.txt"
                    "piece length" 262144
                    "pieces" (make-pieces 2)}
          torrent-bytes (make-single-file-torrent-bytes)
          result (torrent/extract-info-dict-bytes torrent-bytes)]
      (is (not (:error result)))
      (let [extracted (:ok result)
            standalone (bencode/encode-bencode info-map)]
        (is (java.util.Arrays/equals ^bytes extracted ^bytes standalone)))))
  (testing "returns error for non-dict input"
    (let [result (torrent/extract-info-dict-bytes (bencode/encode-bencode "not a dict"))]
      (is (:error result))))
  (testing "returns error when info key missing"
    (let [result (torrent/extract-info-dict-bytes
                  (bencode/encode-bencode {"announce" "http://example.com"}))]
      (is (:error result)))))

(deftest compute-info-hash-test
  (testing "returns 20-byte hash"
    (let [torrent-bytes (make-single-file-torrent-bytes)
          result (torrent/compute-info-hash torrent-bytes)]
      (is (not (:error result)))
      (is (= 20 (alength ^bytes (:ok result))))))
  (testing "hash matches manual sha1 of info dict bytes"
    (let [torrent-bytes (make-single-file-torrent-bytes)
          info-bytes (:ok (torrent/extract-info-dict-bytes torrent-bytes))
          expected (bencode/sha1-hash info-bytes)
          actual (:ok (torrent/compute-info-hash torrent-bytes))]
      (is (java.util.Arrays/equals ^bytes expected ^bytes actual)))))

(deftest extract-announce-urls-test
  (testing "extracts primary announce URL"
    (let [decoded (sorted-map "announce" "http://tracker.example.com/announce"
                              "info" {})
          result (torrent/extract-announce-urls decoded)]
      (is (= "http://tracker.example.com/announce" (:announce result)))))
  (testing "extracts announce-list tiers"
    (let [decoded (sorted-map "announce" "http://primary.com/announce"
                              "announce-list" [["http://tier1a.com" "http://tier1b.com"]
                                               ["http://tier2.com"]]
                              "info" {})
          result (torrent/extract-announce-urls decoded)]
      (is (= "http://primary.com/announce" (:announce result)))
      (is (= [["http://tier1a.com" "http://tier1b.com"]
              ["http://tier2.com"]]
             (:announce-list result)))))
  (testing "missing announce-list returns nil for that key"
    (let [decoded (sorted-map "announce" "http://tracker.com/announce"
                              "info" {})
          result (torrent/extract-announce-urls decoded)]
      (is (nil? (:announce-list result))))))

;; ---------------------------------------------------------------------------
;; GROUP 6: Full torrent parser
;; ---------------------------------------------------------------------------

(deftest parse-pieces-test
  (testing "splits byte array into 20-byte chunks"
    (let [raw (make-pieces 3)
          result (torrent/parse-pieces raw)]
      (is (= 3 (count result)))
      (is (every? #(= 20 (alength ^bytes %)) result))))
  (testing "empty input returns empty vector"
    (is (= [] (torrent/parse-pieces (byte-array 0)))))
  (testing "each chunk has correct content"
    (let [raw (make-pieces 2)
          [p0 p1] (torrent/parse-pieces raw)]
      (is (every? #(= 0 %) (seq p0)))
      (is (every? #(= 1 %) (seq p1))))))

(deftest parse-info-dict-single-file-test
  (testing "parses single-file info dict"
    (let [info-map {"length" 500000
                    "name" "test.txt"
                    "piece length" 262144
                    "pieces" (make-pieces 2)}
          result (torrent/parse-info-dict info-map)]
      (is (not (:error result)))
      (let [info (:ok result)]
        (is (= "test.txt" (:name info)))
        (is (= 262144 (:piece-length info)))
        (is (= 2 (count (:pieces info))))
        (is (= 500000 (:length info)))
        (is (nil? (:files info)))))))

(deftest parse-info-dict-multi-file-test
  (testing "parses multi-file info dict"
    (let [info-map {"files" [{"length" 100 "path" ["dir" "file1.txt"]}
                             {"length" 200 "path" ["dir" "file2.txt"]}]
                    "name" "my-torrent"
                    "piece length" 262144
                    "pieces" (make-pieces 1)}
          result (torrent/parse-info-dict info-map)]
      (is (not (:error result)))
      (let [info (:ok result)]
        (is (= "my-torrent" (:name info)))
        (is (= 262144 (:piece-length info)))
        (is (= 1 (count (:pieces info))))
        (is (nil? (:length info)))
        (is (= 2 (count (:files info))))
        (is (= {:path ["dir" "file1.txt"] :length 100} (first (:files info))))
        (is (= {:path ["dir" "file2.txt"] :length 200} (second (:files info))))))))

(deftest parse-torrent-single-file-test
  (testing "parses complete single-file torrent"
    (let [torrent-bytes (make-single-file-torrent-bytes)
          result (torrent/parse-torrent torrent-bytes)]
      (is (not (:error result)))
      (let [t (:ok result)]
        (is (= "http://tracker.example.com/announce" (:announce t)))
        (is (nil? (:announce-list t)))
        (is (= "test.txt" (get-in t [:info :name])))
        (is (= 262144 (get-in t [:info :piece-length])))
        (is (= 2 (count (get-in t [:info :pieces]))))
        (is (= 500000 (get-in t [:info :length])))
        (is (= 20 (alength ^bytes (:info-hash t))))))))

(deftest parse-torrent-multi-file-test
  (testing "parses complete multi-file torrent"
    (let [torrent-bytes (make-multi-file-torrent-bytes)
          result (torrent/parse-torrent torrent-bytes)]
      (is (not (:error result)))
      (let [t (:ok result)]
        (is (= "my-torrent" (get-in t [:info :name])))
        (is (= 2 (count (get-in t [:info :files]))))
        (is (nil? (get-in t [:info :length])))))))

(deftest parse-torrent-optional-fields-test
  (testing "extracts optional fields when present"
    (let [info {"length" 500000
                "name" "test.txt"
                "piece length" 262144
                "pieces" (make-pieces 2)}
          torrent-bytes (bencode/encode-bencode
                         {"announce" "http://tracker.example.com/announce"
                          "comment" "A test torrent"
                          "created by" "test-suite"
                          "creation date" 1234567890
                          "encoding" "UTF-8"
                          "info" info})
          result (torrent/parse-torrent torrent-bytes)]
      (is (not (:error result)))
      (let [t (:ok result)]
        (is (= "A test torrent" (:comment t)))
        (is (= "test-suite" (:created-by t)))
        (is (= 1234567890 (:creation-date t)))
        (is (= "UTF-8" (:encoding t)))))))

(deftest parse-torrent-error-test
  (testing "missing info dict returns error"
    (let [torrent-bytes (bencode/encode-bencode
                         {"announce" "http://tracker.example.com/announce"})
          result (torrent/parse-torrent torrent-bytes)]
      (is (:error result))))
  (testing "invalid bencode returns error"
    (let [result (torrent/parse-torrent (.getBytes "not bencode" "UTF-8"))]
      (is (:error result)))))

;; ---------------------------------------------------------------------------
;; GROUP 7: Validation
;; ---------------------------------------------------------------------------

(deftest validate-required-fields-test
  (testing "valid torrent returns no errors"
    (let [t {:announce "http://tracker.example.com/announce"
             :info {:name "test.txt"
                    :piece-length 262144
                    :pieces [(byte-array 20)]}}]
      (is (empty? (torrent/validate-required-fields t)))))
  (testing "missing announce returns error"
    (let [t {:info {:name "test.txt"
                    :piece-length 262144
                    :pieces [(byte-array 20)]}}]
      (is (some #(re-find #"announce" (:message %))
                (torrent/validate-required-fields t)))))
  (testing "missing info returns error"
    (let [t {:announce "http://example.com"}]
      (is (some #(re-find #"info" (:message %))
                (torrent/validate-required-fields t)))))
  (testing "missing name in info returns error"
    (let [t {:announce "http://example.com"
             :info {:piece-length 262144
                    :pieces [(byte-array 20)]}}]
      (is (some #(re-find #"name" (:message %))
                (torrent/validate-required-fields t)))))
  (testing "missing piece-length in info returns error"
    (let [t {:announce "http://example.com"
             :info {:name "test.txt"
                    :pieces [(byte-array 20)]}}]
      (is (some #(re-find #"piece-length" (:message %))
                (torrent/validate-required-fields t)))))
  (testing "missing pieces in info returns error"
    (let [t {:announce "http://example.com"
             :info {:name "test.txt"
                    :piece-length 262144}}]
      (is (some #(re-find #"pieces" (:message %))
                (torrent/validate-required-fields t))))))

(deftest validate-field-types-test
  (testing "valid types return no errors"
    (let [t {:announce "http://example.com"
             :info {:name "test.txt"
                    :piece-length 262144
                    :pieces [(byte-array 20)]
                    :length 500000}}]
      (is (empty? (torrent/validate-field-types t)))))
  (testing "non-integer piece-length returns error"
    (let [t {:announce "http://example.com"
             :info {:name "test.txt"
                    :piece-length "not a number"
                    :pieces [(byte-array 20)]}}]
      (is (some #(re-find #"piece-length" (:message %))
                (torrent/validate-field-types t)))))
  (testing "non-integer length returns error"
    (let [t {:announce "http://example.com"
             :info {:name "test.txt"
                    :piece-length 262144
                    :pieces [(byte-array 20)]
                    :length "not a number"}}]
      (is (some #(re-find #"length" (:message %))
                (torrent/validate-field-types t))))))

(deftest validate-pieces-length-test
  (testing "valid 20-byte pieces return no errors"
    (let [t {:info {:pieces [(byte-array 20) (byte-array 20)]}}]
      (is (empty? (torrent/validate-pieces-length t)))))
  (testing "non-20-byte piece returns error"
    (let [t {:info {:pieces [(byte-array 20) (byte-array 15)]}}]
      (is (seq (torrent/validate-pieces-length t))))))

(deftest validate-piece-length-test
  (testing "positive piece-length returns no errors"
    (let [t {:info {:piece-length 262144}}]
      (is (empty? (torrent/validate-piece-length t)))))
  (testing "zero piece-length returns error"
    (let [t {:info {:piece-length 0}}]
      (is (seq (torrent/validate-piece-length t)))))
  (testing "negative piece-length returns error"
    (let [t {:info {:piece-length -1}}]
      (is (seq (torrent/validate-piece-length t))))))

(deftest validate-torrent-test
  (testing "valid torrent returns {:ok true}"
    (let [t {:announce "http://tracker.example.com/announce"
             :info {:name "test.txt"
                    :piece-length 262144
                    :pieces [(byte-array 20) (byte-array 20)]
                    :length 500000}}]
      (is (= {:ok true} (torrent/validate-torrent t)))))
  (testing "multiple errors are aggregated"
    (let [t {:info {:piece-length 0
                    :pieces [(byte-array 15)]}}
          result (torrent/validate-torrent t)]
      (is (vector? (:error result)))
      (is (> (count (:error result)) 1)))))

(deftest bencode-error-positions-test
  (testing "truncated string includes position"
    (let [result (bencode/decode-bencode (.getBytes "4:sp" "UTF-8"))]
      (is (= :bencode-parse-error (:error result)))
      (is (number? (:position result)))))
  (testing "invalid integer includes position"
    (let [result (bencode/decode-bencode (.getBytes "i03e" "UTF-8"))]
      (is (= :bencode-parse-error (:error result)))
      (is (number? (:position result))))))

(deftest error-messages-descriptive-test
  (testing "bencode errors have non-empty messages"
    (let [result (bencode/decode-bencode (.getBytes "x" "UTF-8"))]
      (is (string? (:message result)))
      (is (pos? (count (:message result))))))
  (testing "torrent errors have context"
    (let [err (bencode/torrent-error "test error" {:field "info"})]
      (is (= {:field "info"} (:context err))))))

(deftest total-size-test
  (testing "single-file info uses :length"
    (is (= 500000 (torrent/total-size {:length 500000}))))
  (testing "multi-file info sums contained file lengths"
    (is (= 300 (torrent/total-size {:files [{:length 100} {:length 200}]}))))
  (testing "missing sizes total zero"
    (is (= 0 (torrent/total-size {})))))

(deftest piece-file-spans-test
  (testing "single-file piece maps to one span at the piece offset"
    (let [info {:name "test.txt" :piece-length 4 :length 8}]
      (is (= {:ok [{:path ["test.txt"] :file-offset 0 :data-offset 0 :length 4}]}
             (torrent/piece-file-spans info 0 4)))
      (is (= {:ok [{:path ["test.txt"] :file-offset 4 :data-offset 0 :length 4}]}
             (torrent/piece-file-spans info 1 4)))))
  (testing "multi-file piece spanning a file boundary splits into two spans"
    (let [info {:name "t" :piece-length 6
                :files [{:path ["a"] :length 4} {:path ["b"] :length 6}]}]
      (is (= {:ok [{:path ["t" "a"] :file-offset 0 :data-offset 0 :length 4}
                   {:path ["t" "b"] :file-offset 0 :data-offset 4 :length 2}]}
             (torrent/piece-file-spans info 0 6)))))
  (testing "short final piece maps only its own bytes"
    (let [info {:name "t" :piece-length 6
                :files [{:path ["a"] :length 4} {:path ["b"] :length 6}]}]
      (is (= {:ok [{:path ["t" "b"] :file-offset 2 :data-offset 0 :length 4}]}
             (torrent/piece-file-spans info 1 4)))))
  (testing "out-of-range piece index is an error"
    (let [info {:name "test.txt" :piece-length 4 :length 8}]
      (is (:error (torrent/piece-file-spans info 2 4)))))
  (testing "path components escaping the output dir are an error"
    (is (:error (torrent/piece-file-spans {:name ".." :piece-length 4 :length 8} 0 4)))
    (is (:error (torrent/piece-file-spans {:name "t" :piece-length 4 :length 8
                                           :files [{:path [".."] :length 8}]} 0 4)))
    (is (:error (torrent/piece-file-spans {:name "a/b" :piece-length 4 :length 8} 0 4)))
    (is (:error (torrent/piece-file-spans {:name "" :piece-length 4 :length 8} 0 4)))))

(deftest piece-file-spans-rejects-unusable-geometry-test
  (testing "a negative or non-integral piece index or byte count is an error"
    (let [info {:name "t" :piece-length 4 :length 8}]
      (is (re-find #"must be valid"
                   (:message (torrent/piece-file-spans info -1 4))))
      (is (re-find #"must be valid"
                   (:message (torrent/piece-file-spans info 0.5 4))))
      (is (re-find #"must be valid"
                   (:message (torrent/piece-file-spans info 0 0))))
      (is (re-find #"must be valid"
                   (:message (torrent/piece-file-spans info 0 -4))))))
  (testing "a missing or non-positive piece length is an error"
    (is (re-find #"positive :piece-length"
                 (:message (torrent/piece-file-spans {:name "t" :length 8} 0 4))))
    (is (re-find #"positive :piece-length"
                 (:message (torrent/piece-file-spans {:name "t" :piece-length 0 :length 8} 0 4))))
    (is (re-find #"positive :piece-length"
                 (:message (torrent/piece-file-spans {:name "t" :piece-length -4 :length 8} 0 4))))))

(deftest output-file-sizes-requires-name-test
  (testing "info without a name cannot yield an output layout"
    (is (re-find #":name"
                 (:message (torrent/output-file-sizes {:piece-length 4 :length 8}))))
    (is (re-find #":name"
                 (:message (torrent/output-file-sizes {:piece-length 4
                                                       :files [{:path ["a"] :length 8}]}))))))

(deftest duplicate-declared-paths-are-rejected-test
  ;; A torrent may declare the same path twice. output-file-sizes collapses
  ;; that into one map entry while piece-file-spans still hands out two
  ;; distinct byte ranges for it, so both land in the same physical file and
  ;; the download completes having lost the overwritten bytes. Reject it in
  ;; the shared layout guard so neither derivation reports success.
  (let [info {:name "t" :piece-length 4
              :files [{:path ["a"] :length 4} {:path ["a"] :length 4}]}]
    (testing "output-file-sizes refuses the collapsed layout"
      (let [result (torrent/output-file-sizes info)]
        (is (:error result))
        (is (re-find #"same output path twice" (:message result)))))
    (testing "piece-file-spans refuses it too, not just the size map"
      (let [result (torrent/piece-file-spans info 0 4)]
        (is (:error result))
        (is (re-find #"same output path twice" (:message result)))))
    (testing "each piece of the duplicated range errors, not only the first"
      (is (:error (torrent/piece-file-spans info 1 4))))))
(testing "a duplicate full path declared through different nesting is the same clash"
  (let [info {:name "t" :piece-length 4
              :files [{:path ["a" "b"] :length 4} {:path ["a" "b"] :length 4}]}]
    (is (:error (torrent/output-file-sizes info)))))

(deftest mutually-exclusive-layout-fields-are-rejected-test
  ;; An info carrying both :length (single-file) and :files (multi-file)
  ;; derives two layouts at once: total-size prefers :length while
  ;; file-layout prefers :files, so spans cover a different byte range from
  ;; the initialized files — e.g. :length 4 with files totaling 8 leaves
  ;; half the declared layout unwritten. The wire format leaves no room for
  ;; both; reject the shape in the shared guard.
  (let [info {:name "t" :piece-length 4 :length 4
              :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}]
    (testing "output-file-sizes refuses the two-layout info"
      (let [result (torrent/output-file-sizes info)]
        (is (:error result))
        (is (re-find #"both :length and :files" (:message result)))))
    (testing "piece-file-spans refuses it too"
      (let [result (torrent/piece-file-spans info 0 4)]
        (is (:error result))
        (is (re-find #"both :length and :files" (:message result))))))
  (testing "an empty :files beside :length is the same clash, not an empty layout"
    (is (:error (torrent/output-file-sizes {:name "t" :piece-length 4
                                            :length 4 :files []})))))

(deftest missing-length-fields-are-rejected-test
  ;; The guard claims exactly one of :length/:files but only refused both
  ;; present. With neither, output-file-sizes returns a size map containing
  ;; nil and init-layout! dies later in setLength instead of refusing the
  ;; malformed layout as :invalid-info. Same one level down: a file entry
  ;; without :length poisons the same map.
  (testing "an info with neither :length nor :files is an error in both derivations"
    (let [info {:name "t" :piece-length 4}
          sizes-result (torrent/output-file-sizes info)
          spans-result (torrent/piece-file-spans info 0 4)]
      (is (:error sizes-result))
      (is (re-find #"either :length or :files" (:message sizes-result)))
      (is (:error spans-result))))
  (testing "a file entry without :length is an error in both derivations"
    (let [info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"]}]}
          sizes-result (torrent/output-file-sizes info)
          spans-result (torrent/piece-file-spans info 0 4)]
      (is (:error sizes-result))
      (is (:error spans-result)))))

(deftest prefix-colliding-paths-are-rejected-test
  ;; ["t" "a"] cannot be both the file one entry claims and the directory
  ;; another needs for ["t" "a" "b"]. No declaration order repairs it: one
  ;; direction fails creating the child through the file, the other fails
  ;; opening the parent as a file — so refuse upfront, in the shared guard.
  (let [info {:name "t" :piece-length 4
              :files [{:path ["a"] :length 4} {:path ["a" "b"] :length 4}]}]
    (testing "both derivations refuse the nested layout"
      (is (:error (torrent/output-file-sizes info)))
      (is (:error (torrent/piece-file-spans info 0 4)))))
  (testing "an empty entry path collides with its siblings the same way"
    (let [info {:name "t" :piece-length 4
                :files [{:path [] :length 4} {:path ["x"] :length 4}]}]
      (is (:error (torrent/output-file-sizes info))))))

(deftest non-integer-lengths-are-rejected-test
  ;; Lengths the derivations cannot honestly process: a string :length
  ;; crashes piece-file-spans in (>= piece-start total) instead of erroring,
  ;; and output-file-sizes happily hands the string downstream, where
  ;; setLength explodes later, far from the lie. Same guard, same reason.
  (testing "a non-integer single-file length is an error in both derivations"
    (let [info {:name "t" :piece-length 4 :length "x"}]
      (is (:error (torrent/output-file-sizes info)))
      (is (:error (torrent/piece-file-spans info 0 4)))))
  (testing "a non-integer file-entry length is an error in both derivations"
    (let [info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length "x"}]}]
      (is (:error (torrent/output-file-sizes info)))
      (is (:error (torrent/piece-file-spans info 0 4)))))
  (testing "zero stays admitted — it is a length, and empty files are real"
    (let [info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length 0}]}]
      (is (:ok (torrent/output-file-sizes info)))
      (is (:ok (torrent/piece-file-spans info 0 4))))))

;; The exclusivity rule, generated rather than hoped for: stest/check over
;; the derivations can only feed them bare maps, whose generator all but
;; never emits both keys — so the rule gets its own generator that always
;; builds the hostile shape, across lengths and file counts.
(defspec both-layout-fields-always-refused-spec 100
  (prop/for-all [length gen/nat
                 file-count (gen/choose 1 3)]
                (let [info {:name "t" :piece-length 1 :length length
                            :files (mapv (fn [file-index]
                                           {:path [(str "f" file-index)]
                                            :length (inc file-index)})
                                         (range file-count))}]
                  (and (:error (torrent/output-file-sizes info))
                       (:error (torrent/piece-file-spans info 0 1))))))

(deftest piece-arithmetic-overflow-is-an-error-test
  ;; Found by stest/check once ::info generated realistic shapes: a huge
  ;; piece-length times a huge index overflows long in (* piece-index
  ;; nominal). Real torrents cannot reach it, but the contract is errors
  ;; as data — never a throw — so overflow reports instead of escaping.
  (testing "overflowing piece arithmetic returns an error, not ArithmeticException"
    (let [info {:name "t" :piece-length 4611686018427387904 :length 8}
          result (torrent/piece-file-spans info 2 4)]
      (is (:error result))
      (is (re-find #"overflow" (:message result))))))

(defspec piece-file-spans-cover-exactly-spec 100
  (prop/for-all
   [piece-length (gen/choose 1 16)
    file-lengths (gen/vector (gen/choose 1 20) 1 4)]
   (let [total (reduce + file-lengths)
         file-count (count file-lengths)
         info (if (= 1 file-count)
                {:name "f" :piece-length piece-length :length total}
                {:name "t" :piece-length piece-length
                 :files (mapv (fn [file-index file-length]
                                {:path [(str "f" file-index)] :length file-length})
                              (range file-count) file-lengths)})
         piece-count (int (Math/ceil (/ total (double piece-length))))
         file-sizes (if (= 1 file-count)
                      {["f"] total}
                      (into {} (map (fn [file-index file-length]
                                      [["t" (str "f" file-index)] file-length])
                                    (range file-count) file-lengths)))]
     (every? true?
             (for [piece-index (range piece-count)]
               (let [piece-start (* piece-index piece-length)
                     expected (min piece-length (- total piece-start))
                     spans (:ok (torrent/piece-file-spans info piece-index expected))
                     lengths (map :length spans)
                     data-offsets (map :data-offset spans)]
                 (and (vector? spans)
                      (= expected (reduce + 0 lengths))
                      (= (vec (butlast (reductions + 0 lengths))) (vec data-offsets))
                      (every? #(and (>= (:file-offset %) 0)
                                    (<= (+ (:file-offset %) (:length %))
                                        (get file-sizes (:path %) -1)))
                              spans))))))))

;; ---------------------------------------------------------------------------
;; Cross-function invariants between output-file-sizes and piece-file-spans.
;; write-layout! looks each spanned path up in the sizes map to truncate it, so
;; a path emitted by one function and absent from the other is an NPE, not a
;; wrong-but-safe result. Neither function's own test checks the agreement.
;; ---------------------------------------------------------------------------

(defn- generated-info
  "Build an info dict for the given piece length and file lengths."
  [piece-length file-lengths]
  (let [total (reduce + 0 file-lengths)
        file-count (count file-lengths)]
    (if (= 1 file-count)
      {:name "f" :piece-length piece-length :length total}
      {:name "t" :piece-length piece-length
       :files (mapv (fn [file-index file-length]
                      {:path [(str "f" file-index)] :length file-length})
                    (range file-count) file-lengths)})))

(defspec output-file-sizes-match-declared-total-spec 100
  (prop/for-all
   [piece-length (gen/choose 1 16)
    file-lengths (gen/vector (gen/choose 0 20) 1 5)]
   (let [info (generated-info piece-length file-lengths)
         sizes (:ok (torrent/output-file-sizes info))]
     (and (some? sizes)
          (= (reduce + 0 file-lengths) (reduce + 0 (vals sizes)))
          (= (count file-lengths) (count sizes))
          (= (set (vals sizes)) (set file-lengths))))))

(defspec piece-span-paths-are-declared-by-output-file-sizes-spec 100
  (prop/for-all
   [piece-length (gen/choose 1 16)
    file-lengths (gen/vector (gen/choose 1 20) 1 5)]
   (let [info (generated-info piece-length file-lengths)
         total (reduce + 0 file-lengths)
         sizes (set (keys (:ok (torrent/output-file-sizes info))))
         piece-count (int (Math/ceil (/ total (double piece-length))))
         span-paths (set (for [piece-index (range piece-count)
                               :let [start (* piece-index piece-length)
                                     len (min piece-length (- total start))]
                               span (:ok (torrent/piece-file-spans info piece-index len))]
                           (:path span)))]
     (every? sizes span-paths))))

(defspec hostile-path-components-never-produce-a-layout-spec 100
  (prop/for-all
   [hostile (gen/elements [".." "." "a/b" "a\\b" "/abs" "" "./.." "f/.."
                           "/etc/passwd" "\\\\host\\share" "~/x" "sub/../../x"])]
   (let [single (:ok (torrent/output-file-sizes {:name hostile :piece-length 4 :length 4}))
         multi (:ok (torrent/output-file-sizes
                     {:name "t" :piece-length 4
                      :files [{:path [hostile] :length 4}]}))
         spans (:ok (torrent/piece-file-spans {:name hostile :piece-length 4 :length 4} 0 4))]
     (and (nil? single) (nil? multi) (nil? spans)))))

;; ---------------------------------------------------------------------------
;; Compiled output layout (issue #31): derived once per download, so the
;; per-piece path no longer walks the declared file list.
;; ---------------------------------------------------------------------------

(deftest compile-output-layout-test
  (testing "single-file info compiles to one entry starting at zero"
    (is (= {:ok {:files [{:path ["test.txt"] :length 8 :start 0}]
                 :sizes {["test.txt"] 8}
                 :total 8
                 :piece-length 4}}
           (torrent/compile-output-layout {:name "test.txt" :piece-length 4 :length 8}))))
  (testing "multi-file entries carry cumulative byte starts"
    (is (= {:ok {:files [{:path ["t" "a"] :length 4 :start 0}
                         {:path ["t" "b"] :length 6 :start 4}]
                 :sizes {["t" "a"] 4 ["t" "b"] 6}
                 :total 10
                 :piece-length 6}}
           (torrent/compile-output-layout {:name "t" :piece-length 6
                                           :files [{:path ["a"] :length 4}
                                                   {:path ["b"] :length 6}]}))))
  (testing "zero-length files ride in sizes but never in span search"
    ;; Empties are created by layout init, yet overlap no piece bytes,
    ;; so the per-piece walk visits exactly the overlapped files.
    (is (= {:ok {:files [{:path ["t" "a"] :length 4 :start 0}
                         {:path ["t" "b"] :length 4 :start 4}]
                 :sizes {["t" "a"] 4 ["t" "empty"] 0 ["t" "b"] 4}
                 :total 8
                 :piece-length 4}}
           (torrent/compile-output-layout {:name "t" :piece-length 4
                                           :files [{:path ["a"] :length 4}
                                                   {:path ["empty"] :length 0}
                                                   {:path ["b"] :length 4}]}))))
  (testing "an unusable layout is an error, not a layout"
    (is (:error (torrent/compile-output-layout {:piece-length 4 :length 8})))
    (is (:error (torrent/compile-output-layout {:name ".." :piece-length 4 :length 8})))
    (is (:error (torrent/compile-output-layout {:name "t" :piece-length 4 :length 8
                                                :files [{:path ["a"] :length 8}]})))
    (is (:error (torrent/compile-output-layout {:name "t" :piece-length 4
                                                :files [{:path ["a"] :length 4}
                                                        {:path ["a"] :length 4}]}))))
  (testing "non-collection :files is an error envelope, never a throw"
    ;; Every entry point into the shared layout guard must answer, since
    ;; info is torrent-controlled and callers handle errors as data.
    (let [info {:name "t" :piece-length 4 :files 42}]
      (is (:error (torrent/compile-output-layout info)))
      (is (:error (torrent/output-file-sizes info)))
      (is (:error (torrent/piece-file-spans info 0 4))))))

(deftest layout-spans-test
  (testing "single-file piece maps to one span at the piece offset"
    (let [layout (:ok (torrent/compile-output-layout {:name "test.txt" :piece-length 4 :length 8}))]
      (is (= {:ok [{:path ["test.txt"] :file-offset 0 :data-offset 0 :length 4}]}
             (torrent/layout-spans layout 0 4)))
      (is (= {:ok [{:path ["test.txt"] :file-offset 4 :data-offset 0 :length 4}]}
             (torrent/layout-spans layout 1 4)))))
  (testing "a piece spanning a file boundary splits into two spans"
    (let [layout (:ok (torrent/compile-output-layout {:name "t" :piece-length 6
                                                      :files [{:path ["a"] :length 4}
                                                              {:path ["b"] :length 6}]}))]
      (is (= {:ok [{:path ["t" "a"] :file-offset 0 :data-offset 0 :length 4}
                   {:path ["t" "b"] :file-offset 0 :data-offset 4 :length 2}]}
             (torrent/layout-spans layout 0 6)))
      (is (= {:ok [{:path ["t" "b"] :file-offset 2 :data-offset 0 :length 4}]}
             (torrent/layout-spans layout 1 4)))))
  (testing "out-of-range piece index is an error"
    (let [layout (:ok (torrent/compile-output-layout {:name "test.txt" :piece-length 4 :length 8}))]
      (is (:error (torrent/layout-spans layout 2 4)))))
  (testing "unusable geometry is an error"
    (let [layout (:ok (torrent/compile-output-layout {:name "t" :piece-length 4 :length 8}))]
      (is (re-find #"must be valid" (:message (torrent/layout-spans layout -1 4))))
      (is (re-find #"must be valid" (:message (torrent/layout-spans layout 0 0))))))
  (testing "a layout without a positive piece length is an error"
    (let [layout (assoc (:ok (torrent/compile-output-layout {:name "t" :piece-length 4 :length 8}))
                        :piece-length nil)]
      (is (re-find #"positive :piece-length" (:message (torrent/layout-spans layout 0 4))))))
  (testing "a hand-built malformed layout is an error, never a throw"
    ;; The per-piece guards are O(1), so entries that slip past them
    ;; must still fail closed instead of throwing mid-search.
    (is (:error (torrent/layout-spans {} 0 4)))
    (is (:error (torrent/layout-spans {:files [] :total 0 :piece-length 4} 0 4)))
    (is (:error (torrent/layout-spans {:files [{}] :total 4 :piece-length 4} 0 4)))
    (is (:error (torrent/layout-spans {:files [42] :total 4 :piece-length 4} 0 4)))
    (is (:error (torrent/layout-spans {:files [{:path ["a"]}] :total 4 :piece-length 4} 0 4)))))

(defspec layout-spans-agrees-with-piece-file-spans-spec 100
  (prop/for-all
   [piece-length (gen/choose 1 16)
    file-lengths (gen/vector (gen/choose 0 20) 1 5)]
   (let [info (generated-info piece-length file-lengths)
         total (reduce + 0 file-lengths)
         layout (:ok (torrent/compile-output-layout info))
         piece-count (int (Math/ceil (/ total (double piece-length))))]
     (and (some? layout)
          (every? true?
                  (for [piece-index (range piece-count)
                        :let [start (* piece-index piece-length)
                              len (min piece-length (- total start))]]
                    (= (torrent/piece-file-spans info piece-index len)
                       (torrent/layout-spans layout piece-index len))))))))

;; ---------------------------------------------------------------------------
;; fdef specs hold generatively (stest/check).
;; Every public fn in this namespace is pure and total over generated
;; inputs, so the whole set is pinned here. A failure names its sym and
;; shrunk counterexample — that pair says exactly which args shape to
;; tighten next (as happened with total-size's ::totalable-info).
;; ---------------------------------------------------------------------------

(deftest fdef-specs-hold-generatively-test
  (testing "every torrent fdef holds over generated inputs"
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.domain.torrent/extract-info-dict-bytes
                      dev.cljtoc.domain.torrent/compute-info-hash
                      dev.cljtoc.domain.torrent/extract-announce-urls
                      dev.cljtoc.domain.torrent/parse-pieces
                      dev.cljtoc.domain.torrent/parse-info-dict
                      dev.cljtoc.domain.torrent/total-size
                      dev.cljtoc.domain.torrent/output-file-sizes
                      dev.cljtoc.domain.torrent/compile-output-layout
                      dev.cljtoc.domain.torrent/layout-spans
                      dev.cljtoc.domain.torrent/piece-file-spans
                      dev.cljtoc.domain.torrent/validate-required-fields
                      dev.cljtoc.domain.torrent/validate-field-types
                      dev.cljtoc.domain.torrent/validate-pieces-length
                      dev.cljtoc.domain.torrent/validate-piece-length
                      dev.cljtoc.domain.torrent/validate-torrent
                      dev.cljtoc.domain.torrent/parse-torrent]
                    50)]
      (is (empty? failures)
          (str "fdef check failures: " (pr-str failures))))))
