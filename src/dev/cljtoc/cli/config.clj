(ns dev.cljtoc.cli.config
  "Client settings file: Transmission settings.json equivalent, EDN edition.

   Every path and limit the client runs with lives here as data. The file
   carries only keys with live consumers today (dirs, the announce port,
   the per-torrent peer cap); future slices add their own keys rather than
   this namespace guessing at them. Unknown keys warn and are dropped, so
   a file written for a newer client still loads.

   The file is the only source for these values; the command line selects
   the file (--config) and the command to run, and sets nothing itself. No
   file at the default location means defaults; an explicit --config path
   that does not exist, or a present-but-unreadable file, is an error naming
   the path — never silent defaults over a typo'd file.

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

(def ^:private user-dir-escapes
  "Characters a backslash escapes inside a double-quoted shell word: the
   quote, the backslash, and the dollar. Every other backslash stays literal,
   which is how the shell reads double quotes."
  #{\" \\ \$})

(defn- parse-user-dir-word
  "Read the user-dirs.dirs value without running it: remove the outer double
   quotes, resolve \\\", \\\\, and \\$ escapes, and leave every other character
   literal. Never evaluates shell syntax, so a directory named \"\\$(id)\" stays
   that name. Returns [value home-prefix?] where home-prefix? says the word
   began with an unescaped $HOME, or nil for an empty word."
  [raw]
  (let [quoted? (str/starts-with? raw "\"")
        start (if quoted? 1 0)
        length (count raw)
        home-prefix? (and (<= (+ start (count "$HOME")) length)
                          (= "$HOME" (subs raw start (+ start (count "$HOME")))))]
    (loop [index start
           out []]
      (if (>= index length)
        (let [value (apply str out)]
          (when (seq value)
            [value home-prefix?]))
        (let [character (.charAt ^String raw index)
              escaped (when (< (inc index) length)
                        (.charAt ^String raw (inc index)))]
          (cond
            (and quoted? (= character \"))
            (let [value (apply str out)]
              (when (seq value)
                [value home-prefix?]))

            (and (= character \\) (contains? user-dir-escapes escaped))
            (recur (+ index 2) (conj out escaped))

            :else
            (recur (inc index) (conj out character))))))))

(defn- parse-xdg-download-dir
  "Extract XDG_DOWNLOAD_DIR from user-dirs.dirs content, expanding only a
   leading unescaped $HOME the way the file itself allows. Returns nil when
   the key is absent or empty, so the caller falls back instead of guessing a
   blank directory. Mirrors transmission's getXdgEntryFromUserDirs: a
   localized or relocated Downloads folder (~/Descargas, /mnt/store/dl) is
   the user's real one."
  [content home]
  (when content
    (when-let [line (some #(when (str/starts-with? % "XDG_DOWNLOAD_DIR=") %)
                          (str/split-lines content))]
      (when-let [[value home-prefix?] (parse-user-dir-word
                                       (subs line (count "XDG_DOWNLOAD_DIR=")))]
        (if home-prefix?
          (str home (subs value (count "$HOME")))
          value)))))

(s/def ::home string?)
(s/def ::separator string?)
(s/def ::user-dirs-content (s/nilable string?))
(s/def ::download-dir-options
  (s/keys :req-un [::home]
          :opt-un [::user-dirs-content ::separator]))

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

(s/fdef default-download-dir
  :args (s/cat :options ::download-dir-options)
  :ret string?)

(defn- user-dirs-content
  "Contents of path, or nil when it is absent or unreadable. Takes the
   path rather than reading config-home itself, so the unreadable branch is
   testable and default-download-dir stays pure."
  [path]
  (let [^java.io.File file (io/file path)]
    (when (.exists file)
      (try (slurp file)
           (catch Exception _ nil)))))

(s/def ::state-dir string?)
(s/def ::piece-cache-dir string?)
(s/def ::output-dir string?)
(s/def ::peer-port (s/int-in 1 65536))
(s/def ::rpc-port (s/int-in 1 65536))
(s/def ::max-peers pos-int?)
(s/def ::settings
  (s/keys :req-un [::state-dir ::piece-cache-dir ::output-dir
                   ::peer-port ::rpc-port ::max-peers]))

(defn default-settings
  "Build the knobs a user can actually change, with Transmission's values:
   port 51413 (TrDefaultPeerPort), 50 peers per torrent
   (TrDefaultPeerLimitTorrent), downloads in ~/Downloads
   (tr_getDefaultDownloadDir), resume state under the config home, the
   piece cache under the cache home, and the RPC port the daemon listens
   on. The port is deliberately not Transmission's 9091: a real
   Transmission daemon on the same host would clash with it. Pure: takes the user-dirs.dirs
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
   :rpc-port 24891
   :max-peers (:max-peers download/default-config)})

(s/fdef default-settings
  :args (s/cat :user-dirs-content (s/nilable string?))
  :ret ::settings)

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
   :peer-port #(s/valid? ::peer-port %)
   :rpc-port #(s/valid? ::rpc-port %)
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

(defn- missing-file-result
  "What a missing config file means: defaults when path is nil (the default
   location is allowed to hold no file), a named refusal for an explicit path
   (a mistyped --config must not look like an empty config)."
  [path resolved defaults]
  (if (nil? path)
    {:ok defaults}
    (config-error (str "Config file not found: " resolved))))

(defn load-settings
  "Load settings from path. When path is nil, read the default location and
   fall back to defaults if it holds no file. An explicit path that does not
   exist is refused, so a mistyped --config cannot look like an empty config.
   A present file parses strictly. Returns {:ok settings} or
   {:error :invalid-config :message msg}."
  ([path]
   (let [resolved (or path (default-config-path))
         ^java.io.File file (io/file resolved)
         defaults (default-settings (user-dirs-content
                                     (str config-home "/user-dirs.dirs")))]
     (cond
       (not (.exists file))
       (missing-file-result path resolved defaults)

       :else
       (try
         (parse-settings (slurp file) defaults)
         (catch Exception exception
           (config-error (str "Could not read config file " resolved
                              ": " (.getMessage exception)))))))))

(s/fdef load-settings
  :args (s/cat :path (s/nilable string?))
  :ret ::settings-result)
