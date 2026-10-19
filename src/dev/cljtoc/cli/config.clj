(ns dev.cljtoc.cli.config
  "Client settings file: Transmission settings.json equivalent, EDN edition.

   Every path and limit the client runs with lives here as data. The file
   carries only keys with live consumers today (dirs, the announce port,
   the per-torrent peer cap); future slices add their own keys rather than
   this namespace guessing at them. Unknown keys warn and are dropped, so
   a file written for a newer client still loads.

   The file is the only source for these values; the command line selects
   the file (--config) and the command to run, and sets nothing itself. A
   missing file means defaults; a present-but-unreadable file is an error,
   never silent defaults over a typo'd file.

   Path values are passed through untouched: java.io.File interprets them,
   so either separator spelling works, including Windows `\\` or `/` in
   the same value. Nothing here does string path math — containment and
   canonicalization belong to the disk port, which appends File/separator
   rather than a literal `/` for exactly this reason."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [dev.cljtoc.orchestration.download :as download]))

(def ^:private home-dir
  (System/getProperty "user.home"))

(def ^:private file-separator
  (System/getProperty "file.separator"))

(defn- home-subdir
  "The env-or-home policy as a pure function: a nonempty absolute env value
   wins, anything else (unset, empty, or relative — the XDG spec also treats
   a relative XDG home as unset) falls back to home-plus-subdir. Pure so the
   policy is testable; the JVM cannot set its own environment."
  [env-value home-dir subdir]
  (if (and (seq env-value)
           (.isAbsolute ^java.io.File (io/file env-value)))
    env-value
    (str home-dir subdir)))

(def config-home
  "Where cljtoc reads config from. One derivation, shared by the default
   config path and the state dir: two derivations disagreed under
   XDG_CONFIG_HOME, moving the state without moving the file."
  (home-subdir (System/getenv "XDG_CONFIG_HOME") home-dir "/.config"))

(def cache-home
  (home-subdir (System/getenv "XDG_CACHE_HOME") home-dir "/.cache"))

(defn- parse-xdg-download-dir
  "Extract XDG_DOWNLOAD_DIR from user-dirs.dirs content, expanding $HOME the
   way the file itself allows. Returns nil when the key is absent or empty,
   so the caller falls back instead of guessing a blank directory.
   Mirrors transmission's getXdgEntryFromUserDirs: a localized or relocated
   Downloads folder (~/Descargas, /mnt/store/dl) is the user's real one."
  [content home]
  (when content
    (when-let [line (some #(when (str/starts-with? % "XDG_DOWNLOAD_DIR=") %)
                          (str/split-lines content))]
      (let [raw (-> line
                    (subs (count "XDG_DOWNLOAD_DIR="))
                    (str/replace "\"" ""))]
        (when (seq raw)
          (str/replace raw "$HOME" home))))))

(defn default-download-dir
  "The directory downloads land in. Honors an XDG_DOWNLOAD_DIR entry from
   user-dirs.dirs (the Linux convention, and the one Transmission reads),
   else <home>/Downloads — which is also correct on macOS and on Windows,
   where user.home is the user's profile directory. Windows relocations through
   FOLDERID_Downloads are not detected: set :output-dir in the settings file
   to override. Pure: reads nothing, the caller gathers the inputs, and
   :separator (the platform's file separator) keeps Windows paths from
   mixing separators."
  [{:keys [home user-dirs-content separator]
    :or {separator "/"}}]
  (or (parse-xdg-download-dir user-dirs-content home)
      (str home separator "Downloads")))

(defn- user-dirs-content
  "Contents of path, or nil when it is absent or unreadable. Takes the
   path rather than reading config-home itself, so the unreadable branch is
   testable and default-download-dir stays pure."
  [path]
  (let [^java.io.File file (io/file path)]
    (when (.exists file)
      (try (slurp file)
           (catch Exception _ nil)))))

(defn default-settings
  "Build the knobs a user can actually change, with Transmission's values:
   port 51413 (TrDefaultPeerPort), 50 peers per torrent
   (TrDefaultPeerLimitTorrent), downloads in ~/Downloads
   (tr_getDefaultDownloadDir), resume state under the config home, the
   piece cache under the cache home. Pure: takes the user-dirs.dirs
   content instead of reading it, so no file is touched at load time or
   on require — the read happens once, in load-settings. Knobs the manager
   reserves but does not enforce (:min-peers, :piece-timeout-ms, and friends)
   stay in orchestration.download/default-config instead of being offered here
   as settings that do nothing."
  [user-dirs-content]
  {:state-dir (str config-home "/cljtoc/state")
   :piece-cache-dir (str cache-home "/cljtoc/pieces")
   :output-dir (default-download-dir
                {:home home-dir
                 :user-dirs-content user-dirs-content
                 :separator file-separator})
   :peer-port 51413
   :max-peers (:max-peers download/default-config)})

(defn- absolute-path?
  "True when value names an absolute path on this platform. Relative values
   are refused rather than resolved against the working directory: the same
   config would otherwise download somewhere different under `lein run` than
   under the jar, and `~/x` is relative to the JVM even though a shell would
   expand it. A malformed value (NUL bytes, and on Windows also <>:|?*)
   makes Path/of throw instead of answering, so that refusal is caught here
   and reported as not-absolute by the caller."
  [value]
  (and (string? value)
       (seq value)
       (try
         (.isAbsolute ^java.nio.file.Path (java.nio.file.Path/of ^String value (into-array String [])))
         (catch java.nio.file.InvalidPathException _ false))))

(def ^:private dir-keys
  "Keys naming a directory, all of which must be absolute."
  #{:state-dir :piece-cache-dir :output-dir})

(def ^:private key-preds
  "Validation per known key. Unknown keys never reach here: parse
   selects the known keys first, warning about the rest."
  {:state-dir absolute-path?
   :piece-cache-dir absolute-path?
   :output-dir absolute-path?
   :peer-port #(and (pos-int? %) (<= % 65535))
   :max-peers pos-int?})

(defn- invalid-value-message
  "Why a value was refused, in the caller's terms. Relative directory
   values get the reason spelled out, and a leading ~ gets its own line:
   a shell expands it, the JVM does not, so it would create a directory
   literally named ~."
  [key-name value]
  (let [prefix (str "Invalid config value for " key-name ": " (pr-str value))]
    (cond
      (not (contains? dir-keys key-name))
      prefix

      (and (string? value) (str/starts-with? value "~"))
      (str prefix " — ~ is not expanded by the client; write the full absolute path")

      :else
      (str prefix " — must be an absolute path, not a path relative to the"
           " working directory"))))

(defn- config-error [message]
  {:error :invalid-config :message message})

(s/def ::config-refusal
  (s/and map? #(= :invalid-config (:error %)) #(string? (:message %))))

(s/def ::settings-result
  (s/or :ok (s/and map? #(map? (:ok %)))
        :error ::config-refusal))

(defn- validate-known
  "Check every supplied known key against its predicate. Returns nil when
   clean, else the refusal envelope naming the first bad key."
  [known]
  (reduce-kv (fn [_ key-name value]
               (let [pred (get key-preds key-name)]
                 (when-not (pred value)
                   (reduced (config-error
                             (invalid-value-message key-name value))))))
             nil
             known))

(defn parse-settings
  "Parse EDN text into a settings map over the defaults. Unknown keys warn
   on stdout and are dropped. Returns {:ok settings} or
   {:error :invalid-config :message msg}.

   The read result is wrapped in {:ok _} rather than inspected directly:
   a document carrying its own :error key must not be mistaken for a
   failed parse, which is what the unwrapped form did."
  [text defaults]
  (let [read-result (try
                      (let [reader (java.io.PushbackReader.
                                    (java.io.StringReader. text))
                            eof-marker (Object.)
                            document (edn/read reader)
                            trailing (edn/read {:eof eof-marker} reader)]
                        (if (identical? trailing eof-marker)
                          {:ok document}
                          (config-error "Could not parse config: trailing data after the settings map")))
                      (catch Exception exception
                        (config-error (str "Could not parse config: "
                                           (.getMessage exception)))))]
    (if (:error read-result)
      read-result
      (let [document (:ok read-result)
            known-keys (keys key-preds)]
        (if-not (map? document)
          (config-error (str "Config must be a map, got: " (pr-str document)))
          (let [known (select-keys document known-keys)
                unknown (apply dissoc document known-keys)]
            (when (seq unknown)
              (println (str "[config] WARNING: ignoring unknown keys: "
                            (pr-str (sort-by pr-str (keys unknown))))))
            (or (validate-known known)
                {:ok (merge defaults known)})))))))

(s/fdef parse-settings
  :args (s/cat :text string? :defaults map?)
  :ret ::settings-result)

(defn default-config-path
  "Where load-settings looks when no path is given: <config home>/cljtoc/config.edn."
  []
  (str config-home "/cljtoc/config.edn"))

(s/fdef default-config-path
  :args (s/cat)
  :ret string?)

(defn load-settings
  "Load settings from path (or the default location when nil). A missing
   file means defaults; a present file parses strictly. Returns
   {:ok settings} or {:error :invalid-config :message msg}."
  ([path]
   (let [resolved (or path (default-config-path))
         ^java.io.File file (io/file resolved)]
     (if-not (.exists file)
       {:ok (default-settings (user-dirs-content
                               (str config-home "/user-dirs.dirs")))}
       (try
         (parse-settings (slurp file)
                         (default-settings (user-dirs-content
                                            (str config-home "/user-dirs.dirs"))))
         (catch Exception exception
           (config-error (str "Could not read config file " resolved
                              ": " (.getMessage exception)))))))))

(s/fdef load-settings
  :args (s/cat :path (s/nilable string?))
  :ret ::settings-result)
