(ns dev.cljtoc.ports.disk-impl
  "Real disk I/O implementation for download orchestration.
   
   Implements the IDiskPort protocol for actual file system operations.
   Includes disk space checking and state persistence."
  (:require [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.domain.torrent :as torrent]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s])
  (:import [java.io File RandomAccessFile]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute BasicFileAttributes]
           [java.util Arrays]))

(defn- containment-prefix
  "A canonical directory as a path prefix for containment checks. The
   separator is appended only when missing: a canonical dir that is itself the
   filesystem root already ends in one, and doubling it yields \"//\", which
   no canonical child can start with — every write refused as :unsafe-path."
  [canonical-dir]
  (if (.endsWith canonical-dir File/separator)
    canonical-dir
    (str canonical-dir File/separator)))

(defn- filesystem-root?
  "True when a canonical path is a filesystem root. Detected by shape rather
   than by spelling: a root is the one path with no parent, which holds for
   the Unix \"/\" and for a Windows drive root alike. Comparing against
   File/separator would match only the Unix root and let a drive root through."
  [canonical-path]
  (and (some? canonical-path)
       (nil? (.getParentFile (io/file canonical-path)))))

(defn- declined-output-dir
  "Policy, not a containment check: refuse to assemble a torrent directly in
   a filesystem root. A caller who passes / almost always means an explicit
   directory, and scattering declared files across a root risks overwriting
   unrelated system paths. Containment holds either way — declared path
   components are still validated — so this only makes the mistake loud and
   deterministic instead of a :write-error that depends on whether the
   process happens to be allowed to write there.
   Returns the error envelope, or nil when the directory is acceptable."
  [output-dir]
  (let [canonical (try
                    (.getCanonicalPath (io/file output-dir))
                    (catch Exception _ nil))]
    (when (filesystem-root? canonical)
      {:error :unsafe-output-dir
       :message (str "Refusing to download into the filesystem root: " output-dir
                     ". Pass an explicit output directory.")})))

(defn- contained?
  "True when out-file resolves inside the canonical output dir."
  [canonical-dir out-file]
  (.startsWith (.getCanonicalPath out-file) (containment-prefix canonical-dir)))

(defn- resolve-contained
  "Resolve relative path components under output-dir for writing.
   Checks containment, then creates missing parents, then checks again. Both
   checks are load-bearing: a pre-existing symlink component would redirect
   the write outside the dir, and checking only after mkdirs would already
   have created that directory out there. Returns {:ok File} or
   {:error :unsafe-path ...}."
  [output-dir file-path]
  (let [out-file (apply io/file output-dir file-path)
        canonical-dir (.getCanonicalPath (io/file output-dir))
        escape (fn [] {:error :unsafe-path
                       :message (str "Output path escapes " output-dir ": " (pr-str file-path))})]
    (if-not (contained? canonical-dir out-file)
      (escape)
      (let [parent (.getParentFile out-file)]
        (when parent
          (.mkdirs parent))
        (if (contained? canonical-dir out-file)
          {:ok out-file}
          (escape))))))

(defn- file-identity
  "Filesystem identity of an existing target for alias detection: the
   store-qualified fileKey, which sees what canonical strings cannot — hard
   links (two names, one inode) and case-folded spellings of an existing
   file. Nil when the target does not exist (nothing to alias yet), when
   the filesystem provides no key, or when the attributes cannot be read;
   unknown identities never group, so this fails open toward the canonical
   check rather than refusing distinct files. Never throws."
  [out-file]
  (try
    (when (.exists ^File out-file)
      (let [path (.toPath ^File out-file)
            key (-> (Files/readAttributes path BasicFileAttributes
                                          (into-array LinkOption []))
                    (.fileKey))]
        (when (some? key)
          [(.name (Files/getFileStore path)) key])))
    (catch Exception _
      nil)))

(defn- cache-file
  "File for one torrent's cached piece: <cache-dir>/<info-hash-hex>/piece-<index>.dat.
   Scoping by content is what keeps concurrent downloads from overwriting
   each other's cached bytes. A missing or malformed hash fails closed here
   (see disk/cache-scope) — and nothing is created before the check runs.
   Returns {:ok File} or disk/invalid-info-hash-error."
  [piece-cache-dir info-hash piece-index]
  (if-let [scope (disk/cache-scope info-hash)]
    {:ok (io/file piece-cache-dir scope (str "piece-" piece-index ".dat"))}
    disk/invalid-info-hash-error))

(defn- snapshot-parent-mtimes
  "Snapshot the last-modified time of every distinct parent directory of
   the resolved entries: {parent-absolute-path mtime-ms}. Taken at prepare
   time so write-prepared! can detect filesystem changes between prepare
   and a later write without re-resolving the whole layout on every piece:
   creating, deleting, renaming, symlinking, or hardlinking any entry
   bumps its parent dir's mtime. Pure map work over the already-resolved
   entries — no extra syscalls beyond what resolve-entries performed.
   Stat follows symlinks, so a logical parent that is itself a link still
   reports its target dir's mtime, which is where entry changes land."
  [entries]
  (into {}
        (comp (map (fn [[_ entry]] (.getParentFile ^File (:file entry))))
              (filter some?)
              (distinct)
              (map (fn [^File parent] [(.getAbsolutePath parent) (.lastModified parent)])))
        entries))

(defn- parent-mtimes-changed?
  "True when any snapshotted parent dir's current mtime differs from the
   prepare-time snapshot. Iterates the snapshot's own keys — prepare
   already distilled every file down to its distinct parent, so this is
   O(distinct-parents) stats with zero per-file work. An empty or missing
   snapshot (hand-built prepared values) counts as changed and degrades
   to the full re-resolve, never to the fast path."
  [snapshot]
  (or (empty? snapshot)
      (boolean
       (some (fn [[parent-path frozen]]
               (not= frozen (.lastModified (io/file parent-path))))
             snapshot))))

(defn- resolve-entries
  "Containment-resolve every declared path under output-dir. Returns
   {:ok {:canonical-dir dir :entries {path {:file File :canonical str
   :identity id-or-nil}}}} or the first {:error ...}. Creates missing
   parent directories as the containment probe requires (see
   resolve-contained) but truncates and writes nothing."
  [output-dir declared-paths]
  (let [canonical-dir (.getCanonicalPath (io/file output-dir))
        resolved (into {} (map (fn [declared-path]
                                 [declared-path (resolve-contained output-dir declared-path)])
                               declared-paths))
        escaped (first (filter #(-> % val :error) resolved))]
    (if escaped
      (val escaped)
      {:ok {:canonical-dir canonical-dir
            :entries (into {}
                           (map (fn [[declared-path envelope]]
                                  (let [out-file (:ok envelope)]
                                    [declared-path
                                     {:file out-file
                                      :canonical (.getCanonicalPath ^File out-file)
                                      :identity (file-identity out-file)}]))
                                resolved))}})))

(defn- alias-error
  "The shared whole-layout alias check: refuse when two declared paths
   share one canonical target or one filesystem identity. Per-path
   containment cannot see those aliases — an in-tree symlink or hard link
   passes for both entries alone, after which their independent ranges
   overwrite one target. Returns the {:error :unsafe-path ...} envelope,
   or nil when the entries are alias-free."
  [entries output-dir]
  (let [canonical-collision (->> entries
                                 (map (fn [[declared-path entry]]
                                        [declared-path (:canonical entry)]))
                                 (group-by second)
                                 (filter #(> (count (second %)) 1))
                                 first)]
    (if canonical-collision
      (let [[target paths] canonical-collision]
        {:error :unsafe-path
         :message (str "Output paths " (pr-str (mapv first paths))
                       " resolve to the same file " target
                       " under " output-dir)})
      (let [identity-collision (->> entries
                                    (map (fn [[declared-path entry]]
                                           [declared-path (:identity entry)]))
                                    (filter (comp some? second))
                                    (group-by second)
                                    (filter #(> (count (second %)) 1))
                                    first)]
        (when identity-collision
          (let [[_ paths] identity-collision]
            {:error :unsafe-path
             :message (str "Output paths " (pr-str (mapv first paths))
                           " refer to the same file on disk under " output-dir)}))))))

(defn- prepare-layout
  "Resolve the whole declared layout once and freeze it into the explicit
   prepared value write-prepared-piece re-validates touched files against:
   the canonical output dir plus, per declared path, the absolute file, its
   canonical target, and its filesystem identity at prepare time. Also
   snapshots the last-modified time of every distinct parent directory
   among the declared paths, so write-prepared-piece can detect filesystem
   changes between prepare and a later write without re-resolving the
   whole layout on every piece.
   Returns {:ok prepared} or {:error ...}."
  [output-dir sizes]
  (let [resolved (resolve-entries output-dir (keys sizes))]
    (if (:error resolved)
      resolved
      (let [{canonical-dir :canonical-dir entries :entries} (:ok resolved)]
        (if-let [collision (alias-error entries output-dir)]
          collision
          {:ok {:layout nil
                :output-dir canonical-dir
                :parent-mtimes (snapshot-parent-mtimes entries)
                :resolved (into {}
                                (map (fn [[declared-path entry]]
                                       [declared-path
                                        {:file (.getAbsolutePath ^File (:file entry))
                                         :canonical (:canonical entry)
                                         :identity (:identity entry)}])
                                     entries))}})))))

(defn- resolve-layout
  "Resolve every declared path under output-dir for writing. Each path is
   containment-checked (see resolve-contained), then the resolved targets
   are rejected when two declared paths share one canonical file or one
   filesystem identity. Per-path containment cannot see those aliases — an
   in-tree symlink or hard link passes for both entries alone, after which
   their independent ranges overwrite one target. Nothing is truncated or
   written by this step.
   ponytail: identity needs an existing file, so two spellings one
   filesystem folds onto a not-yet-created file (A/a, neither present)
   still slip through — realpath keeps the spelling as given and there is
   no inode to compare. Closed the moment either spelling exists.
   Returns {:ok {declared-path File}} or {:error ...}."
  [output-dir declared-paths]
  (let [resolved (resolve-entries output-dir declared-paths)]
    (if (:error resolved)
      resolved
      (let [entries (:entries (:ok resolved))]
        (if-let [collision (alias-error entries output-dir)]
          collision
          {:ok (into {}
                     (map (fn [[declared-path entry]]
                            [declared-path (:file entry)])
                          entries))})))))

(defn- stable-touch?
  "True when a touched path still names what prepare saw: same canonical
   target, and the same file once both sides have an identity to compare. A
   target that did not exist at prepare time (nil identity then) is accepted
   on its canonical alone: prepare runs before init on the standalone path,
   and the init-created file cannot alias anything prepare already cleared.
   An unreadable-or-vanished target (nil now) likewise falls back to the
   canonical check — file-identity fails open by design — and the write
   itself recreates a merely deleted file, exactly as the full path would."
  [frozen live]
  (and (= (:canonical frozen) (:canonical live))
       (or (nil? (:identity frozen))
           (nil? (:identity live))
           (= (:identity frozen) (:identity live)))))

(defn- revalidate-touched
  "Re-resolve only the touched declared paths under output-dir: containment
   plus the mkdirs probe per path (see resolve-contained), then the current
   canonical target and filesystem identity. O(touched) syscalls — the fast
   path when no parent directory has changed since prepare. Returns
   {:ok {path {:file File :canonical str :identity id-or-nil}}} or the
   first {:error ...}."
  [output-dir touched-paths]
  (loop [remaining touched-paths
         current {}]
    (if (empty? remaining)
      {:ok current}
      (let [declared-path (first remaining)
            probed (resolve-contained output-dir declared-path)]
        (if (:error probed)
          probed
          (let [out-file (:ok probed)]
            (recur (rest remaining)
                   (assoc current declared-path
                          {:file out-file
                           :canonical (.getCanonicalPath ^File out-file)
                           :identity (file-identity out-file)}))))))))

(defn- write-prepared!
  "Blocking write of one piece through the prepared layout: re-validate
   touched files against the prepared snapshot, then open, size, and write
   only the touched files. Uses a parent-directory mtime gate to decide
   whether to take the fast touched-only path (one stat per distinct
   parent dir plus O(touched) re-validation) or
   fall back to the full re-resolve (O(files) syscalls):

   - If no parent directory's mtime has changed since prepare, the
     prepared snapshot is still valid for untouched paths, so re-validate
     only the touched paths and check touched-side stability against the
     frozen snapshot. This is the common case: no filesystem changes
     between pieces.
   - If any parent directory's mtime has changed, the prepared snapshot
     may be stale (a file was created, deleted, or renamed in a parent
     dir), so re-resolve the whole layout and run the full alias check.
     This catches post-prepare aliases involving untouched paths (e.g. b
     symlinked onto a after prepare, with the next piece writing only a).

   The alias check is the only way to catch a post-prepare alias involving
   an untouched path — a touched-only scan is enough to catch a touched
   file moved or aliased to another touched file, but cannot see an
   untouched path newly redirected onto a touched target. The parent-mtime
   gate makes the common case flat-cost while preserving correctness when
   the filesystem changes. Residual: the gate trusts parent-dir mtimes,
   so an alias planted inside the clock granularity — or by an actor able
   to rewrite a parent's mtime afterwards — defeats detection the way any
   TOCTOU race defeats a check-then-act sequence. The threat model is
   accidental or tool-driven mutation, not a mtime-spoofing local
   adversary (who can rewrite output files directly).

   Stability is checked touched-side against the frozen snapshot: a
   touched path that has moved since prepare is refused with :unsafe-path,
   with no open and no partial write. Callers prepare AFTER init
   (run-download does), so frozen entries normally carry an identity; a
   nil frozen identity is accepted on the canonical alone (the init-created
   file cannot alias anything prepare already cleared), and a nil live
   identity falls back to the canonical check with the write recreating a
   merely deleted file, exactly as the full path would.
   Returns {:ok :written} or {:error ...}."
  [prepared output-dir spans bytes]
  (let [sizes (:sizes (:layout prepared))
        touched (distinct (map :path spans))
        parent-mtimes (:parent-mtimes prepared)
        parents-changed (parent-mtimes-changed? parent-mtimes)]
    (if parents-changed
      ;; Full re-resolve: some parent dir changed, so the prepared snapshot
      ;; may be stale. O(files) syscalls, same as the full write path.
      (let [live-result (resolve-entries output-dir (keys sizes))]
        (if (:error live-result)
          live-result
          (let [{canonical-dir :canonical-dir live :entries} (:ok live-result)
                frozen (:resolved prepared)
                moved (first (filter (fn [declared-path]
                                       (not (stable-touch?
                                             (get frozen declared-path)
                                             (get live declared-path))))
                                     touched))]
            (if (and (not moved)
                     (not= canonical-dir (:output-dir prepared)))
              {:error :unsafe-path
               :message (str "Output directory changed on disk since layout preparation: "
                             canonical-dir " != " (:output-dir prepared))}
              (if moved
                {:error :unsafe-path
                 :message (str "Output path " (pr-str moved)
                               " changed on disk since layout preparation under " output-dir)}
                (if-let [collision (alias-error live output-dir)]
                  collision
                  (try
                    (doseq [declared-path touched]
                      (let [out-file (get-in live [declared-path :file])]
                        (with-open [raf (RandomAccessFile. out-file "rw")]
                          (.setLength raf (get sizes declared-path))
                          (doseq [{file-offset :file-offset
                                   data-offset :data-offset
                                   span-length :length}
                                  (filter #(= declared-path (:path %)) spans)]
                            (let [slice (Arrays/copyOfRange
                                         ^bytes bytes
                                         (int data-offset)
                                         (int (+ data-offset span-length)))]
                              (.seek raf file-offset)
                              (.write raf slice))))))
                    {:ok :written}
                    (catch Exception error
                      {:error :write-error :message (.getMessage error)}))))))))
      ;; Fast path: no parent dir changed, so the prepared snapshot is still
      ;; valid for untouched paths. Re-validate only the touched paths.
      ;; O(touched) syscalls.
      (let [live-result (revalidate-touched output-dir touched)]
        (if (:error live-result)
          live-result
          (let [live (:ok live-result)
                frozen (:resolved prepared)
                moved (first (filter (fn [declared-path]
                                       (not (stable-touch?
                                             (get frozen declared-path)
                                             (get live declared-path))))
                                     touched))]
            (if moved
              {:error :unsafe-path
               :message (str "Output path " (pr-str moved)
                             " changed on disk since layout preparation under " output-dir)}
              (try
                (doseq [declared-path touched]
                  (let [out-file (get-in live [declared-path :file])]
                    (with-open [raf (RandomAccessFile. out-file "rw")]
                      (.setLength raf (get sizes declared-path))
                      (doseq [{file-offset :file-offset
                               data-offset :data-offset
                               span-length :length}
                              (filter #(= declared-path (:path %)) spans)]
                        (let [slice (Arrays/copyOfRange
                                     ^bytes bytes
                                     (int data-offset)
                                     (int (+ data-offset span-length)))]
                          (.seek raf file-offset)
                          (.write raf slice))))))
                {:ok :written}
                (catch Exception error
                  {:error :write-error :message (.getMessage error)})))))))))

(defn- write-layout!
  "Blocking write of one piece into the torrent file layout. Every declared
   target is validated (symlink-contained, alias-free across the whole
   layout — an alias between a touched path and an untouched one corrupts
   just the same), but only files this piece overlaps are opened, truncated
   to their declared length, and written. Returns {:ok :written} or
   {:error ...}."
  [output-dir sizes spans bytes]
  (let [touched (group-by :path spans)
        layout-result (resolve-layout output-dir (keys sizes))]
    (if (:error layout-result)
      layout-result
      (try
        (doseq [[declared-path file-spans] touched]
          (let [out-file (get (:ok layout-result) declared-path)]
            (with-open [raf (RandomAccessFile. out-file "rw")]
              (.setLength raf (get sizes declared-path))
              (doseq [{file-offset :file-offset
                       data-offset :data-offset
                       span-length :length} file-spans]
                (let [slice (Arrays/copyOfRange ^bytes bytes
                                                (int data-offset)
                                                (int (+ data-offset span-length)))]
                  (.seek raf file-offset)
                  (.write raf slice))))))
        {:ok :written}
        (catch Exception error
          {:error :write-error :message (.getMessage error)})))))

(defn- init-layout!
  "Blocking creation of every declared output path at its declared length,
   including zero-length files. Every path is resolved (symlink-contained,
   alias-free across the layout) before the first file is truncated.
   Returns {:ok :initialized} or {:error ...}."
  [output-dir sizes]
  (let [layout-result (resolve-layout output-dir (keys sizes))]
    (if (:error layout-result)
      layout-result
      (try
        (doseq [[declared-path declared-length] sizes]
          (with-open [raf (RandomAccessFile. (get (:ok layout-result) declared-path) "rw")]
            (.setLength raf declared-length)))
        {:ok :initialized}
        (catch Exception error
          {:error :write-error :message (.getMessage error)})))))

(defrecord DiskPortImpl
           [state-dir
            piece-cache-dir
            config]

  disk/IDiskPort
  (read-torrent-file [_ path]
    (try
      (let [file (io/file path)]
        (if (.exists file)
          (let [bytes (Files/readAllBytes (.toPath file))
                parse-result (torrent/parse-torrent bytes)]
            (if (:error parse-result)
              {:error :invalid-torrent :message (str "Failed to parse: " (get-in parse-result [:error :message]))}
              {:ok (:ok parse-result)}))
          {:error :file-not-found :message (str "File not found: " path)}))
      (catch Exception e
        {:error :read-error :message (.getMessage e)})))

  (read-piece [_ info-hash piece-index]
    (let [file-result (cache-file piece-cache-dir info-hash piece-index)]
      (if (:error file-result)
        file-result
        (try
          (let [piece-file (:ok file-result)]
            (if (.exists piece-file)
              {:ok (Files/readAllBytes (.toPath piece-file))}
              {:ok nil}))
          (catch Exception e
            {:error :read-error :message (.getMessage e)})))))

  (write-piece [_ info-hash piece-index bytes]
    (let [file-result (cache-file piece-cache-dir info-hash piece-index)]
      (if (:error file-result)
        file-result
        (try
          (let [piece-file (:ok file-result)
                parent (.getParentFile piece-file)]
            (when-not (.exists parent)
              (.mkdirs parent))
            (clojure.java.io/copy bytes piece-file)
            {:ok :written})
          (catch Exception e
            {:error :write-error :message (.getMessage e)})))))

  (write-output-piece [_ layout output-dir piece-index bytes]
    (try
      (let [spans-result (torrent/layout-spans layout piece-index (alength ^bytes bytes))
            sizes (:sizes layout)
            declined (declined-output-dir output-dir)]
        ;; No sizes-error branch: a compiled layout carries the sizes
        ;; the spans were derived from, so spans-result is the only
        ;; derivation that can fail.
        (cond
          declined
          declined

          (:error spans-result)
          {:error :invalid-info
           :message (str "Cannot map piece " piece-index ": "
                         (:message spans-result))}

          (not (disk/valid-output-layout? layout))
          disk/invalid-output-layout-error

          (not (every? #(contains? sizes (:path %)) (:ok spans-result)))
          disk/invalid-output-layout-error

          :else
          (write-layout! output-dir
                         sizes
                         (:ok spans-result)
                         bytes)))
      (catch Exception error
        {:error :write-error :message (.getMessage error)})))

  (write-prepared-piece [_ prepared output-dir piece-index bytes]
    (try
      (let [layout (:layout prepared)
            spans-result (torrent/layout-spans layout piece-index (alength ^bytes bytes))
            sizes (:sizes layout)
            declined (declined-output-dir output-dir)]
        ;; No sizes-error branch: a prepared layout carries the layout the
        ;; spans were derived from, so spans-result is the only derivation
        ;; that can fail — mirroring write-output-piece.
        (cond
          declined
          declined

          (:error spans-result)
          {:error :invalid-info
           :message (str "Cannot map piece " piece-index ": "
                         (:message spans-result))}

          (not (disk/valid-output-layout? layout))
          disk/invalid-output-layout-error

          ;; O(touched), not O(files): the full prepared invariant held at
          ;; prepare time; this write only needs its own entries present.
          (not (disk/writable-prepared? prepared (map :path (:ok spans-result))))
          disk/invalid-prepared-layout-error

          (not= (.getCanonicalPath (io/file output-dir))
                (:output-dir prepared))
          disk/invalid-prepared-layout-error

          (not (every? #(contains? sizes (:path %)) (:ok spans-result)))
          disk/invalid-output-layout-error

          :else
          (write-prepared! prepared
                           output-dir
                           (:ok spans-result)
                           bytes)))
      (catch Exception error
        {:error :write-error :message (.getMessage error)})))

  (initialize-output-layout [_ layout output-dir]
    (try
      (let [sizes (:sizes layout)
            declined (declined-output-dir output-dir)]
        (cond
          declined
          declined

          (not (disk/consistent-output-layout? layout))
          disk/invalid-output-layout-error

          :else
          (init-layout! output-dir sizes)))
      (catch Exception error
        {:error :write-error :message (.getMessage error)})))

  (prepare-output-layout [_ layout output-dir]
    (try
      (let [sizes (:sizes layout)
            declined (declined-output-dir output-dir)]
        (cond
          declined
          declined

          (not (disk/consistent-output-layout? layout))
          disk/invalid-output-layout-error

          :else
          (let [prepared (prepare-layout output-dir sizes)]
            (if (:error prepared)
              prepared
              {:ok (assoc (:ok prepared) :layout layout)}))))
      (catch Exception error
        {:error :write-error :message (.getMessage error)})))

  (ensure-directory [_ path]
    (try
      (let [dir (io/file path)]
        (when-not (.exists dir)
          (.mkdirs dir))
        {:ok :created})
      (catch Exception e
        {:error :mkdir-error :message (.getMessage e)})))

  (save-state [_ download]
    (try
      (let [state-file (io/file state-dir (str (:id download) ".edn"))]
        (when-not (.exists state-dir)
          (.mkdirs state-dir))
        (spit state-file (pr-str (disk/encode-state download)))
        {:ok :saved})
      (catch Exception e
        {:error :save-error :message (.getMessage e)})))

  (load-state [_ id]
    (try
      (let [state-file (io/file state-dir (str id ".edn"))]
        (if (.exists state-file)
          {:ok (disk/decode-state (edn/read-string (slurp state-file)))}
          {:ok nil}))
      (catch Exception e
        {:error :load-error :message (.getMessage e)})))

  (delete-state [_ id]
    (try
      (let [state-file (io/file state-dir (str id ".edn"))]
        (when (.exists state-file)
          (.delete state-file))
        {:ok :deleted})
      (catch Exception e
        {:error :delete-error :message (.getMessage e)}))))

(defn create
  "Create a DiskPortImpl instance.
   
   Options:
   - :state-dir - directory for download state (default: ./torrent-state)
   - :piece-cache-dir - directory for cached pieces (default: ./torrent-cache)"
  ([]
   (create {}))
  ([{:keys [state-dir piece-cache-dir config]
     :or {state-dir "./torrent-state"
          piece-cache-dir "./torrent-cache"}}]
   (->DiskPortImpl (io/file state-dir)
                   (io/file piece-cache-dir)
                   config)))

(s/fdef create
  :args (s/cat :opts (s/? map?))
  :ret any?)

(defn available-space
  "Get available disk space in bytes for the given path."
  [path]
  (try
    (let [file (io/file path)]
      (.getUsableSpace file))
    (catch Exception _
      nil)))

(s/fdef available-space
  :args (s/cat :path any?)
  :ret (s/nilable nat-int?))

(defn ensure-directory
  "Ensure a directory exists, creating it if necessary."
  [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (.mkdirs file))
    file))

(s/fdef ensure-directory
  :args (s/cat :path any?)
  :ret any?)

(defn check-disk-space
  "Check if there's enough disk space for the torrent.
   Returns {:ok true} if space is sufficient, {:error :insufficient-space} if not."
  [path required-bytes]
  (let [_ (ensure-directory path)
        available (available-space path)]
    (if (and available (pos? available) (< available required-bytes))
      {:error :insufficient-space
       :message (format "Insufficient disk space: need %s bytes, have %s bytes"
                        required-bytes available)}
      {:ok true})))

(s/fdef check-disk-space
  :args (s/cat :path any? :required-bytes nat-int?)
  :ret map?)


