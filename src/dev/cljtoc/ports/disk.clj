(ns dev.cljtoc.ports.disk
  "Disk I/O port protocol for file operations.
  
   This protocol defines the contract for all disk I/O operations
   needed by the download orchestration layer: reading .torrent files,
   writing piece data, and persisting download state."
  (:require [dev.cljtoc.domain.bencode :as bencode]
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
    "Write verified piece data to the piece cache under piece-index.
     This is not the final file layout — that is write-output-piece's job,
     which maps the bytes into output-dir via the compiled output layout.
     Returns a channel that will deliver {:ok :written} or {:error reason}.

     Side effects: writes to filesystem")

  (write-output-piece [this layout output-dir piece-index bytes]
    "Write one verified piece into the torrent file layout under output-dir.
     layout is the compiled output layout for the download
     (domain.torrent/compile-output-layout), derived once at download
     start so the per-piece path does no layout arithmetic of its own.
     Single-file layouts land at output-dir/<name>; multi-file layouts
     at output-dir/<name>/<path...>, splitting pieces that cross a file
     boundary. Returns a channel that will deliver {:ok :written} or
     {:error reason}.

      Side effects: writes to filesystem")

  (initialize-output-layout [this layout output-dir]
    "Create every declared output path under output-dir at its declared
     length, including zero-length files. layout is the compiled output
     layout for the download (domain.torrent/compile-output-layout).
     Runs once at download start so a torrent with no pieces still
     materializes its empty files, and so piece writes only touch files
     they overlap.
     Returns a channel that will deliver {:ok :initialized} or {:error reason}.

     Side effects: creates files and directories")

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

(defn valid-output-layout?
  "True when layout has the compiled shape the disk port methods take:
   a map carrying a sizes map, a files vector, a natural total, and a
   positive piece length. Both DiskPortImpl and MockDiskPort refuse
   anything else with :invalid-info before recording or opening
   anything, so orchestration tests cannot miss an invalid layout at
   either entry point — init accepts exactly what piece writes accept."
  [layout]
  (and (map? layout)
       (map? (:sizes layout))
       (vector? (:files layout))
       (nat-int? (:total layout))
       (let [nominal (:piece-length layout)]
         (and (integer? nominal) (pos? nominal)))))

(s/fdef valid-output-layout?
  :args (s/cat :layout any?)
  :ret boolean?)

(defn consistent-output-layout?
  "True when layout carries the full compiled-layout invariants: shaped
   entries and sizes, every searched file declared in sizes at an equal
   length, the total equal to the declared sizes, and file starts
   chaining contiguously from zero to the total. O(files): enforced once
   at initialization, never per piece — per-piece writes check the O(1)
   gate above plus touched-path membership instead, so the hot path
   stays flat in file count while both entries agree on validity."
  [layout]
  (and (valid-output-layout? layout)
       (let [{files :files sizes :sizes total :total} layout]
         (and (every? (fn [entry]
                        (and (map? entry)
                             (vector? (:path entry))
                             (seq (:path entry))
                             (every? string? (:path entry))
                             (nat-int? (:length entry))
                             (nat-int? (:start entry))))
                      files)
              (every? (fn [[declared-path declared-length]]
                        (and (vector? declared-path)
                             (seq declared-path)
                             (every? string? declared-path)
                             (nat-int? declared-length)))
                      sizes)
              (every? (fn [{file-path :path file-length :length}]
                        (= file-length (get sizes file-path ::missing)))
                      files)
              (= total (reduce +' 0 (vals sizes)))
              (or (empty? files)
                  (let [ends (map (fn [entry] (+ (:start entry) (:length entry))) files)]
                    ;; vec on both sides: butlast answers nil where map
                    ;; answers (), and sequential = tells them apart.
                    (and (= 0 (:start (first files)))
                         (= (vec (map :start (rest files)))
                            (vec (butlast ends)))
                         (= total (last ends)))))))))

(s/fdef consistent-output-layout?
  :args (s/cat :layout any?)
  :ret boolean?)

(def invalid-output-layout-error
  "The :invalid-info envelope both disk ports return when handed something
   that is not a compiled output layout. One shared literal so DiskPortImpl
   and MockDiskPort cannot drift apart on the message."
  {:error :invalid-info
   :message "Invalid output layout: not a compiled output layout"})

;; ---------------------------------------------------------------------------
;; Shared state encoding — the single persistence seam.
;; Both the async DiskPortImpl and the sync cli-state adapter persist
;; {:id string} files in the same layout using these helpers, so state
;; written by one seam loads through the other.
;; ---------------------------------------------------------------------------

(s/def ::hex-string (s/and string? #(even? (count %)) #(re-matches #"[0-9a-f]*" %)))

(defn hex-string->bytes
  "Parse a lowercase hex string back into a byte array."
  [hex-string]
  (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16))
                   (partition 2 hex-string))))

(s/fdef hex-string->bytes
  :args (s/cat :hex-string ::hex-string)
  :ret bytes?)

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

(s/fdef encode-state
  :args (s/cat :download map?)
  :ret map?)

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

(s/fdef decode-state
  :args (s/cat :data any?)
  :ret any?)

(defn id-from-path
  "Generate the canonical human-readable download ID from a torrent file path."
  [torrent-path]
  (let [file-name (.getName (io/file torrent-path))
        [_ ext] (re-find #"\.([^.]+)$" file-name)]
    (if (and ext (not (empty? ext)))
      (subs file-name 0 (- (count file-name) (inc (count ext))))
      file-name)))

(s/fdef id-from-path
  :args (s/cat :torrent-path string?)
  :ret string?)
