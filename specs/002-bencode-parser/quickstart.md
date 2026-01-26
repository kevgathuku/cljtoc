# Quickstart: Bencode Parser & Torrent Domain

**Feature**: 002-bencode-parser
**Namespace**: `cljtoc.domain.bencode` and `cljtoc.domain.torrent`

This guide shows how to use the bencode parser and torrent metadata extractor.

---

## Basic Bencode Decoding

```clojure
(ns my-app.core
  (:require [cljtoc.domain.bencode :as bencode]))

;; Decode a bencode string
(bencode/decode-bencode (.getBytes "4:spam"))
;; => {:ok "spam"}

;; Decode a bencode integer
(bencode/decode-bencode (.getBytes "i42e"))
;; => {:ok 42}

;; Decode a bencode list
(bencode/decode-bencode (.getBytes "li1ei2ei3ee"))
;; => {:ok [1 2 3]}

;; Decode a bencode dictionary
(bencode/decode-bencode (.getBytes "d3:bar4:spam3:fooi42ee"))
;; => {:ok {"bar" "spam", "foo" 42}}
```

---

## Basic Bencode Encoding

```clojure
;; Encode a string
(bencode/encode-bencode "spam")
;; => #bytes "4:spam"

;; Encode an integer
(bencode/encode-bencode 42)
;; => #bytes "i42e"

;; Encode a list
(bencode/encode-bencode [1 2 3])
;; => #bytes "li1ei2ei3ee"

;; Encode a map (keys will be sorted)
(bencode/encode-bencode {"foo" 42 "bar" "spam"})
;; => #bytes "d3:bar4:spam3:fooi42ee"
```

---

## Round-Trip Encoding/Decoding

```clojure
(let [original {"name" "example"
                "numbers" [1 2 3]
                "nested" {"key" "value"}}
      encoded (bencode/encode-bencode original)
      {:keys [ok error]} (bencode/decode-bencode encoded)]
  (= original ok))  ;; => true
```

---

## Parse a Torrent File

```clojure
(ns my-app.torrent
  (:require [cljtoc.domain.torrent :as torrent]
            [clojure.java.io :as io]))

;; Read .torrent file
(defn read-torrent-file [filepath]
  (with-open [in (io/input-stream filepath)]
    (let [bytes (byte-array (.available in))]
      (.read in bytes)
      bytes)))

;; Parse the torrent
(let [torrent-bytes (read-torrent-file "example.torrent")
      {:keys [ok error]} (torrent/parse-torrent torrent-bytes)]
  (if ok
    (do
      (println "Torrent name:" (get-in ok [:info :name]))
      (println "Piece length:" (get-in ok [:info :piece-length]))
      (println "Number of pieces:" (count (get-in ok [:info :pieces])))
      (println "Tracker:" (:announce ok))
      (println "Info hash:" (apply str (map #(format "%02x" %) (:info-hash ok)))))
    (println "Error:" (:message error))))
```

---

## Extract Info Hash

```clojure
;; Compute info hash from .torrent file bytes
(let [torrent-bytes (read-torrent-file "example.torrent")
      {:keys [ok error]} (torrent/compute-info-hash torrent-bytes)]
  (if ok
    (println "Info hash (hex):" (apply str (map #(format "%02x" %) ok)))
    (println "Error:" (:message error))))
```

---

## Handle Single-File Torrents

```clojure
(let [{:keys [ok]} (torrent/parse-torrent (read-torrent-file "single-file.torrent"))
      info (:info ok)]
  (when (:length info)  ;; single-file indicator
    (println "File:" (:name info))
    (println "Size:" (:length info) "bytes")))
```

---

## Handle Multi-File Torrents

```clojure
(let [{:keys [ok]} (torrent/parse-torrent (read-torrent-file "multi-file.torrent"))
      info (:info ok)]
  (when (:files info)  ;; multi-file indicator
    (println "Directory:" (:name info))
    (doseq [file (:files info)]
      (println "  File:" (clojure.string/join "/" (:path file)))
      (println "    Size:" (:length file) "bytes"))))
```

---

## Validate Torrent Structure

```clojure
(let [{:keys [ok error-torrent]} (torrent/parse-torrent torrent-bytes)
      {:keys [ok-valid error-valid]} (torrent/validate-torrent ok)]
  (if ok-valid
    (println "Torrent is valid!")
    (do
      (println "Torrent has validation errors:")
      (doseq [err error-valid]
        (println "  -" (:field err) ":" (:message err))))))
```

---

## Error Handling

```clojure
;; Decode with error handling
(let [{:keys [ok error]} (bencode/decode-bencode malformed-bytes)]
  (if ok
    (process-data ok)
    (case (:error error)
      :bencode-parse-error (println "Parse error at position" (:position error) ":" (:message error))
      :unexpected-eof (println "File truncated:" (:message error))
      (println "Unknown error:" error))))

;; Parse with error handling
(let [{:keys [ok error]} (torrent/parse-torrent torrent-bytes)]
  (if ok
    (use-torrent ok)
    (case (:error error)
      :invalid-torrent (println "Invalid torrent:" (:message error) "\nContext:" (:context error))
      :type-mismatch (println "Type error in field" (:field error))
      (println "Parse failed:" error))))
```

---

## Property-Based Testing Example

```clojure
(ns my-app.bencode-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [cljtoc.domain.bencode :as bencode]))

;; Test that encoding and decoding is reversible
(def bencode-roundtrip
  (prop/for-all [value (gen/one-of [gen/string-ascii
                                     gen/large-integer
                                     (gen/vector gen/large-integer)
                                     (gen/map gen/string-ascii gen/large-integer)])]
    (let [encoded (bencode/encode-bencode value)
          {:keys [ok]} (bencode/decode-bencode encoded)]
      (= value ok))))

(tc/quick-check 1000 bencode-roundtrip)
;; => {:result true, :num-tests 1000, ...}
```

---

## Performance Tips

1. **Reuse byte arrays**: Don't convert strings to bytes repeatedly
2. **Batch operations**: Process multiple torrents in parallel (they're pure functions)
3. **Info hash caching**: Store computed info hash to avoid re-computation
4. **Lazy parsing**: Only parse fields you need (future enhancement)

---

## Common Patterns

### Extract Tracker List (with fallback)

```clojure
(defn get-all-trackers [torrent-metainfo]
  (let [primary (:announce torrent-metainfo)
        backups (flatten (:announce-list torrent-metainfo []))]
    (vec (distinct (cons primary backups)))))
```

### Calculate Total Size

```clojure
(defn total-size [torrent-info]
  (if-let [length (:length torrent-info)]
    length  ;; single-file
    (reduce + (map :length (:files torrent-info)))))  ;; multi-file
```

### Format Info Hash for Display

```clojure
(defn format-info-hash [info-hash-bytes]
  (apply str (map #(format "%02x" %) info-hash-bytes)))

;; Usage:
;; (format-info-hash (:info-hash torrent))
;; => "abcdef1234567890abcdef1234567890abcdef12"
```

---

## Next Steps

After parsing torrents, you'll want to:

1. **Announce to tracker** (Feature 003) - Use announce URL and info hash
2. **Request pieces from peers** (Feature 004) - Use piece hashes for verification
3. **Manage downloads** (Feature 006) - Use file metadata for disk storage

This feature provides the foundation for all BitTorrent operations.
