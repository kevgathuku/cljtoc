(ns dev.cljtoc.ports.disk
  "Disk I/O port protocol for file operations.
  
   This protocol defines the contract for all disk I/O operations
   needed by the download orchestration layer: reading .torrent files,
   writing piece data, and persisting download state.

   Every method returns its {:ok ...} / {:error reason :message msg}
   envelope directly, by return value, and every method blocks until it
   has one. Nothing here runs work on a pool, so a caller that wants two
   calls in flight has to put them on threads of its own -- that choice
   belongs above this seam, not inside it."
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(defprotocol IDiskPort
  "Abstraction for disk operations needed by download orchestration."

  (read-torrent-file [this path]
    "Read and parse a .torrent file from disk.
     Returns {:ok torrent-metadata} or {:error reason :message msg}.
     
     Side effects: reads file from filesystem")

  (read-piece [this info-hash piece-index]
    "Read one torrent's cached piece data from disk.
     The cache is scoped by content (see cache-scope), so concurrent
     downloads never share entries unless their bytes verify identically.
     Returns {:ok bytes}, {:ok nil} when not cached, or {:error ...} when
     the info hash cannot name a cache entry.

     Side effects: reads from piece cache")

  (write-piece [this info-hash piece-index bytes]
    "Write verified piece data to the torrent's piece cache under piece-index.
     This is not the final file layout — that is write-output-piece's job,
     which maps the bytes into output-dir via the compiled output layout.
     Returns {:ok :written} or {:error ...} when the info hash cannot
     name a cache entry.

     Side effects: writes to filesystem")

  (write-output-piece [this layout output-dir piece-index bytes]
    "Write one verified piece into the torrent file layout under output-dir.
     layout is the compiled output layout for the download
     (domain.torrent/compile-output-layout), derived once at download
     start so the per-piece path does no layout arithmetic of its own.
     Single-file layouts land at output-dir/<name>; multi-file layouts
     at output-dir/<name>/<path...>, splitting pieces that cross a file
     boundary. Returns {:ok :written} or {:error reason :message msg}.

      Side effects: writes to filesystem")

  (write-prepared-piece [this prepared output-dir piece-index bytes]
    "Write one verified piece through an explicitly prepared layout
     (prepare-output-layout), re-validating live on every piece. A
     parent-directory mtime gate decides the depth: when no parent dir
     changed since prepare, only the touched files are re-resolved and
     checked for touched-side stability against the snapshot (one stat
     per distinct parent plus O(touched) work — flat in file count for
     the usual layouts where many files share few dirs); when a parent
     changed, the whole layout is re-resolved and the full alias check
     runs, catching a post-prepare alias involving an untouched path
     (e.g. b symlinked onto a after prepare, with the next piece writing
     only a). A touched-only scan alone is enough for touched-vs-touched
     collisions but cannot see an untouched path newly redirected onto a
     touched target, so the fallback — not the gate — carries the alias
     guarantee. Returns {:ok :written} or {:error reason :message msg}.

     Side effects: writes to filesystem")
  (initialize-output-layout [this layout output-dir]
    "Create every declared output path under output-dir at its declared
     length, including zero-length files. layout is the compiled output
     layout for the download (domain.torrent/compile-output-layout).
     Runs once at download start so a torrent with no pieces still
     materializes its empty files, and so piece writes only touch files
     they overlap.
     Returns {:ok :initialized} or {:error reason :message msg}.

     Side effects: creates files and directories")

  (prepare-output-layout [this layout output-dir]
    "Resolve the whole declared layout under output-dir once, up front.
     layout is the compiled output layout for the download
     (domain.torrent/compile-output-layout). Runs the full containment
     and alias checks a per-piece write cannot afford, and returns the
     explicit prepared value write-prepared-piece re-validates touched
     files against — threaded by the caller like the compiled layout,
     never hidden inside the port.
     Returns {:ok prepared} or {:error reason :message msg}.

     Side effects: creates missing parent directories (containment probing)")

  (ensure-directory [this path]
    "Ensure a directory exists, creating it if necessary.
     Returns {:ok :created} or {:error reason :message msg}.
     
     Side effects: creates directories")

  (save-state [this download]
    "Persist download state to disk for pause/resume support.
     Returns {:ok :saved} or {:error reason :message msg}.
     
     Side effects: writes state file")

  (load-state [this id]
    "Load persisted download state from disk.
     Returns {:ok download}, or {:ok nil} when no state exists for id.
     
     Side effects: reads from filesystem")

  (delete-state [this id]
    "Delete persisted download state.
     Returns {:ok :deleted} or {:error reason :message msg}.
     
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

(defn valid-prepared-layout?
  "True when prepared is the explicit value prepare-output-layout returns:
   a map carrying the compiled layout it was resolved from, the canonical
   output dir string it was resolved under, a parent-mtimes map (parent
   directory path to last-modified time, empty for the mock port), and a
   resolved entry per declared path (absolute file string, canonical
   target string, and the filesystem identity seen at prepare time, nil
   when the target did not exist yet). Both DiskPortImpl and MockDiskPort
   refuse anything else with :invalid-info before writing anything."
  [prepared]
  (and (map? prepared)
       (valid-output-layout? (:layout prepared))
       (string? (:output-dir prepared))
       (map? (:parent-mtimes prepared))
       (map? (:resolved prepared))
       (= (set (keys (:resolved prepared)))
          (set (keys (:sizes (:layout prepared)))))
       (every? (fn [[declared-path entry]]
                 (and (vector? declared-path)
                      (seq declared-path)
                      (every? string? declared-path)
                      (map? entry)
                      (string? (:file entry))
                      (string? (:canonical entry))
                      (contains? entry :identity)))
               (:resolved prepared))))

(s/fdef valid-prepared-layout?
  :args (s/cat :prepared any?)
  :ret boolean?)

(def invalid-prepared-layout-error
  "The :invalid-info envelope both disk ports return when handed something
   that is not a prepared output layout. One shared literal so the ports
   cannot drift apart on the message."
  {:error :invalid-info
   :message "Invalid prepared layout: not a prepared output layout"})

(defn writable-prepared?
  "Per-piece gate over a prepared layout: true when prepared is a map
   carrying an O(1)-shaped compiled layout and a resolved entry with a
   canonical string for every path in touched. O(touched): the full
   prepared invariant (resolved entries for exactly the declared paths)
   was established once at prepare time and is pinned by
   valid-prepared-layout? — re-proving it per piece would cost O(files)
   pure work for entries this write never reads."
  [prepared touched]
  (and (map? prepared)
       (valid-output-layout? (:layout prepared))
       (map? (:resolved prepared))
       (every? (fn [declared-path]
                 (let [entry (get (:resolved prepared) declared-path)]
                   (and (map? entry) (string? (:canonical entry)))))
               touched)))

(s/fdef writable-prepared?
  :args (s/cat :prepared any? :touched any?)
  :ret boolean?)

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

(defn- encoded-bytes-tag?
  "True when node already wears the encoded-bytes tag in restorable form:
   exactly {:cljtoc/bytes hex} over an even-length hex string.
   decode-state restores exactly these nodes, so encode-state passes them
   through untouched (idempotent) and refuses anything else in the tag's
   shape — a bad tag stored now is a throw (or, on odd-length input, a
   silently dropped nibble) at the next load."
  [node]
  (and (map? node)
       (= #{:cljtoc/bytes} (set (keys node)))
       (let [hex (:cljtoc/bytes node)]
         (and (string? hex)
              (even? (count hex))
              (boolean (re-matches #"[0-9a-fA-F]*" hex))))))

(defn encode-state
  "Convert a download to EDN-safe data: records become plain maps and
   byte arrays become {:cljtoc/bytes hex} tagged maps. The tag keeps the
   encoding self-describing so decode-state can restore bytes without a
   schema (raw pr-str of byte arrays does not round-trip — it emits
   #object tags that edn/read-string rejects). A pre-existing tag in
   restorable form passes through untouched; anything else wearing the
   tag's shape throws, failing fast at the trust boundary instead of
   storing a value the next load cannot restore."
  [download]
  (walk/postwalk
   (fn [node]
     (cond
       (record? node) (into {} node)
       (bytes? node) {:cljtoc/bytes (bencode/bytes->hex-string node)}
       (encoded-bytes-tag? node) node
       (and (map? node) (= #{:cljtoc/bytes} (set (keys node))))
       (throw (ex-info (str "Invalid :cljtoc/bytes tag "
                            "(even-length hex required): "
                            (pr-str node))
                       {:tag node}))
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
      (let [stripped (subs file-name 0 (- (count file-name) (inc (count ext))))]
        ;; A dotfile torrent would otherwise id as "" (.torrent) or "."
        ;; (..torrent): unusable as a state filename, and the download would
        ;; fail on its first verified piece. Fall back to the full filename,
        ;; which always names one entry.
        (if (contains? #{"" "." ".."} stripped)
          file-name
          stripped))
      file-name)))

(s/fdef id-from-path
  :args (s/cat :torrent-path string?)
  :ret string?)

(defn cache-scope
  "Filesystem-safe cache scope for one torrent: lowercase hex of its info-hash.
   Content-addressed, so identical torrents share entries (their bytes verify
   identically) while distinct torrents never collide — including names that
   differ only by case, which share a directory on case-insensitive
   filesystems, and Windows-reserved spellings, which hex cannot produce.
   Returns the scope string, or nil when the hash is not a non-empty byte array."
  [info-hash]
  (when (and (bytes? info-hash)
             (pos? (alength ^bytes info-hash)))
    (bencode/bytes->hex-string info-hash)))

(s/fdef cache-scope
  :args (s/cat :info-hash any?)
  :ret (s/nilable string?))

(def invalid-info-hash-error
  {:error :invalid-info-hash
   :message "Cannot name a piece-cache entry without a torrent info-hash."})
