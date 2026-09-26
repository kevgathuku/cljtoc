(ns dev.cljtoc.ports.disk
  "Disk I/O port protocol for file operations.
  
   This protocol defines the contract for all disk I/O operations
   needed by the download orchestration layer: reading .torrent files,
   writing piece data, and persisting download state."
  (:require [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.domain.bencode :as bencode]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(defprotocol IDiskPort
  "Abstraction for disk operations needed by download orchestration."
  
  (read-torrent-file [this path]
    "Read and parse a .torrent file from disk.
     Returns a channel that will deliver TorrentMetadata or error.
     
     Side effects: reads file from filesystem")
  
  (read-piece [this piece-index]
    "Read cached piece data from disk.
     Returns a channel that will deliver bytes or nil if not cached.
     
     Side effects: reads from piece cache")
  
  (write-piece [this piece-index bytes]
    "Write verified piece data to disk in the correct file layout.
     Returns a channel that will deliver :ok or {:error reason}.
     
     Side effects: writes to filesystem")
  
  (ensure-directory [this path]
    "Ensure a directory exists, creating it if necessary.
     Returns a channel that will deliver :ok or {:error reason}.
     
     Side effects: creates directories")
  
  (save-state [this download]
    "Persist download state to disk for pause/resume support.
     Returns a channel that will deliver :ok or {:error reason}.
     
     Side effects: writes state file")
  
  (load-state [this id]
    "Load persisted download state from disk.
     Returns a channel that will deliver {:ok download} or {:ok nil}
     when no state exists for id.
     
     Side effects: reads from filesystem")
  
  (delete-state [this id]
    "Delete persisted download state.
     Returns a channel that will deliver :ok or {:error reason}.
     
     Side effects: deletes file"))

;; ---------------------------------------------------------------------------
;; Shared state encoding — the single persistence seam.
;; Both the async DiskPortImpl and the sync cli-state adapter persist
;; {:id string} files in the same layout using these helpers, so state
;; written by one seam loads through the other.
;; ---------------------------------------------------------------------------

(defn hex-string->bytes
  "Parse a lowercase hex string back into a byte array."
  [hex-string]
  (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                   (partition 2 hex-string))))

(defn encode-state
  "Convert a download to EDN-safe data: records become plain maps and
   byte arrays become {:cljtoc/bytes hex} tagged maps. The tag keeps the
   encoding self-describing so decode-state can restore bytes without a
   schema (raw pr-str of byte arrays does not round-trip — it emits
   #object tags that edn/read-string rejects)."
  [download]
  (walk/postwalk
    (fn [node]
      (cond
        (record? node) (into {} node)
        (bytes? node) {:cljtoc/bytes (bencode/bytes->hex-string node)}
        :else node))
    download))

(defn decode-state
  "Restore tagged {:cljtoc/bytes hex} maps produced by encode-state back
   into byte arrays. Applied on every load path so a resumed download
   carries real bytes into handshake and piece verification."
  [data]
  (walk/postwalk
    (fn [node]
      (if (and (map? node)
               (= #{:cljtoc/bytes} (set (keys node)))
               (string? (:cljtoc/bytes node)))
        (hex-string->bytes (:cljtoc/bytes node))
        node))
    data))

(defn id-from-path
  "Generate the canonical human-readable download ID from a torrent file path."
  [torrent-path]
  (let [file-name (.getName (io/file torrent-path))
        [_ ext] (re-find #"\.([^.]+)$" file-name)]
    (if (and ext (not (empty? ext)))
      (subs file-name 0 (- (count file-name) (inc (count ext))))
      file-name)))

(s/def ::hex-string (s/and string? #(even? (count %)) #(re-matches #"[0-9a-f]*" %)))

(s/fdef hex-string->bytes
  :args (s/cat :hex-string ::hex-string)
  :ret bytes?)

(s/fdef encode-state
  :args (s/cat :download map?)
  :ret map?)

(s/fdef decode-state
  :args (s/cat :data any?)
  :ret any?)

(s/fdef id-from-path
  :args (s/cat :torrent-path string?)
  :ret string?)
