(ns dev.cljtoc.domain.torrent-test
  (:require [clojure.test :refer :all]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.domain.bencode :as bencode]))

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
