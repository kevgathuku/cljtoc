(ns dev.cljtoc.ports.disk-impl
  "Real disk I/O implementation for download orchestration.
   
   Implements the IDiskPort protocol for actual file system operations.
   Includes disk space checking and state persistence."
  (:require [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.domain.torrent :as torrent]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as async])
  (:import [java.io File FileInputStream FileOutputStream RandomAccessFile]
           [java.nio.file Files Paths]
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

(defn- write-layout!
  "Blocking write of one piece into the torrent file layout. Only files this
   piece overlaps are opened; each is resolved (symlink-contained), truncated
   to its declared length, then the spans land. Returns {:ok :written} or
   {:error ...}."
  [output-dir sizes spans bytes]
  (let [touched (group-by :path spans)
        resolved (into {} (map (fn [declared-path]
                                 [declared-path (resolve-contained output-dir declared-path)])
                               (keys touched)))
        escaped (first (filter #(-> % val :error) resolved))]
    (if escaped
      (val escaped)
      (try
        (doseq [[declared-path file-spans] touched]
          (let [out-file (:ok (get resolved declared-path))]
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
   including zero-length files. Returns {:ok :initialized} or {:error ...}."
  [output-dir sizes]
  (let [resolved (into {} (map (fn [[declared-path _]]
                                 [declared-path (resolve-contained output-dir declared-path)])
                               sizes))
        escaped (first (filter #(-> % val :error) resolved))]
    (if escaped
      (val escaped)
      (try
        (doseq [[declared-path declared-length] sizes]
          (with-open [raf (RandomAccessFile. (:ok (get resolved declared-path)) "rw")]
            (.setLength raf declared-length)))
        {:ok :initialized}
        (catch Exception error
          {:error :write-error :message (.getMessage error)})))))

(defrecord DiskPortImpl
           [state-dir
            piece-cache-dir
            config]

  disk/IDiskPort
  (read-torrent-file [this path]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [file (io/file path)]
            (if (.exists file)
              (let [bytes (Files/readAllBytes (.toPath file))
                    parse-result (torrent/parse-torrent bytes)]
                (if (:error parse-result)
                  (async/>! ch {:error :invalid-torrent :message (str "Failed to parse: " (get-in parse-result [:error :message]))})
                  (async/>! ch {:ok (:ok parse-result)})))
              (async/>! ch {:error :file-not-found :message (str "File not found: " path)})))
          (catch Exception e
            (async/>! ch {:error :read-error :message (.getMessage e)}))))
      ch))

  (read-piece [this piece-index]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [piece-file (io/file piece-cache-dir (str "piece-" piece-index ".dat"))]
            (if (.exists piece-file)
              (async/>! ch {:ok (Files/readAllBytes (.toPath piece-file))})
              (async/>! ch {:ok nil})))
          (catch Exception e
            (async/>! ch {:error :read-error :message (.getMessage e)}))))
      ch))

  (write-piece [this piece-index bytes]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [piece-file (io/file piece-cache-dir (str "piece-" piece-index ".dat"))
                parent (.getParentFile piece-file)]
            (when-not (.exists parent)
              (.mkdirs parent))
            (clojure.java.io/copy bytes piece-file)
            (async/>! ch {:ok :written}))
          (catch Exception e
            (async/>! ch {:error :write-error :message (.getMessage e)}))))
      ch))

  (write-output-piece [this info output-dir piece-index bytes]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [spans-result (torrent/piece-file-spans info piece-index (alength ^bytes bytes))
                sizes-result (torrent/output-file-sizes info)
                declined (declined-output-dir output-dir)]
            ;; No sizes-error branch: every size error comes from the layout
            ;; guard piece-file-spans also runs, so spans-result is always
            ;; the first to fail.
            (cond
              declined
              (async/>! ch declined)

              (:error spans-result)
              (async/>! ch {:error :invalid-info
                            :message (str "Cannot map piece " piece-index ": "
                                          (:message spans-result))})

              :else
              (async/>! ch (write-layout! output-dir
                                          (:ok sizes-result)
                                          (:ok spans-result)
                                          bytes))))
          (catch Exception error
            (async/>! ch {:error :write-error :message (.getMessage error)}))))
      ch))

  (initialize-output-layout [this info output-dir]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [sizes-result (torrent/output-file-sizes info)
                declined (declined-output-dir output-dir)]
            (cond
              declined
              (async/>! ch declined)

              (:error sizes-result)
              (async/>! ch {:error :invalid-info :message (:message sizes-result)})

              :else
              (async/>! ch (init-layout! output-dir (:ok sizes-result)))))
          (catch Exception error
            (async/>! ch {:error :write-error :message (.getMessage error)}))))
      ch))

  (ensure-directory [this path]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [dir (io/file path)]
            (when-not (.exists dir)
              (.mkdirs dir))
            (async/>! ch {:ok :created}))
          (catch Exception e
            (async/>! ch {:error :mkdir-error :message (.getMessage e)}))))
      ch))

  (save-state [this download]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [state-file (io/file state-dir (str (:id download) ".edn"))]
            (when-not (.exists state-dir)
              (.mkdirs state-dir))
            (spit state-file (pr-str (disk/encode-state download)))
            (async/>! ch {:ok :saved}))
          (catch Exception e
            (async/>! ch {:error :save-error :message (.getMessage e)}))))
      ch))

  (load-state [this id]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [state-file (io/file state-dir (str id ".edn"))]
            (if (.exists state-file)
              (let [data (disk/decode-state (edn/read-string (slurp state-file)))]
                (async/>! ch {:ok data}))
              (async/>! ch {:ok nil})))
          (catch Exception e
            (async/>! ch {:error :load-error :message (.getMessage e)}))))
      ch))

  (delete-state [this id]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [state-file (io/file state-dir (str id ".edn"))]
            (when (.exists state-file)
              (.delete state-file))
            (async/>! ch {:ok :deleted}))
          (catch Exception e
            (async/>! ch {:error :delete-error :message (.getMessage e)}))))
      ch)))

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

(defn get-torrent-size
  "Get total size of torrent from metadata."
  [torrent-metadata]
  (torrent/total-size torrent-metadata))

(s/fdef get-torrent-size
  :args (s/cat :torrent-metadata map?)
  :ret nat-int?)
