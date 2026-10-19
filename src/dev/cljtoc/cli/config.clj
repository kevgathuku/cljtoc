(ns dev.cljtoc.cli.config
  "Client settings file: Transmission settings.json equivalent, EDN edition.

   Every path and limit the client runs with lives here as data. The file
   carries only keys with live consumers today (dirs, manager knobs, the
   announce port); future slices add their own keys rather than this
   namespace guessing at them. Unknown keys warn and are dropped, so a
   file written for a newer client still loads.

   Precedence, weakest first: built-in defaults, the settings file, then
   the CLI invocation (positional args like output-dir). A missing file
   means defaults; a present-but-unreadable file is an error, never
   silent defaults over a typo'd file."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(set! *warn-on-reflection* true)

(defn- home-dir []
  (System/getProperty "user.home"))

(defn- config-home []
  (or (System/getenv "XDG_CONFIG_HOME") (str (home-dir) "/.config")))

(defn- cache-home []
  (or (System/getenv "XDG_CACHE_HOME") (str (home-dir) "/.cache")))

(def default-settings
  "Every knob the client reads. Dirs follow the Transmission layout:
   downloads land in ~/Downloads, resume state lives under the config
   home, the piece cache under the cache home. The peer port (51413)
   and per-torrent peer cap (50) are Transmission's tried-and-tested
   defaults (TrDefaultPeerPort, TrDefaultPeerLimitTorrent). Manager
   knobs mirror orchestration.download/default-config; the CLI hands
   them to the manager as overrides."
  {:state-dir (str (config-home) "/cljtoc/state")
   :piece-cache-dir (str (cache-home) "/cljtoc")
   :output-dir (str (home-dir) "/Downloads")
   :peer-port 51413
   :max-peers 50
   :min-peers 5
   :request-queue-size 16
   :piece-timeout-ms 30000
   :tracker-announce-interval-ms 1800000})

(def ^:private key-preds
  "Validation per known key. Unknown keys never reach here: parse
   selects the known keys first, warning about the rest."
  {:state-dir string?
   :piece-cache-dir string?
   :output-dir string?
   :peer-port #(and (pos-int? %) (<= % 65535))
   :max-peers pos-int?
   :min-peers nat-int?
   :request-queue-size nat-int?
   :piece-timeout-ms nat-int?
   :tracker-announce-interval-ms nat-int?})

(defn- config-error [message]
  {:error :invalid-config :message message})

(defn- validate-known
  "Check every supplied known key against its predicate. Returns nil
   when clean, else the refusal envelope naming the first bad key."
  [known]
  (reduce-kv (fn [_ key-name value]
               (let [pred (get key-preds key-name)]
                 (when-not (pred value)
                   (reduced (config-error
                             (str "Invalid config value for " key-name
                                  ": " (pr-str value)))))))
             nil
             known))

(defn parse-settings
  "Parse EDN text into a settings map over the defaults. Unknown keys
   warn on stdout and are dropped. Returns {:ok settings} or
   {:error :invalid-config :message msg}."
  [text]
  (let [parsed (try
                 (edn/read-string text)
                 (catch Exception exception
                   (reduced (config-error
                             (str "Could not parse config: "
                                  (.getMessage exception))))))]
    (if (:error parsed)
      parsed
      (if-not (map? parsed)
        (config-error (str "Config must be a map, got: " (pr-str parsed)))
        (let [unknown (apply dissoc parsed (keys key-preds))]
          (when (seq unknown)
            (println (str "[config] WARNING: ignoring unknown keys: "
                          (pr-str (sort (keys unknown))))))
          (if-let [refusal (validate-known (select-keys parsed (keys key-preds)))]
            refusal
            {:ok (merge default-settings (select-keys parsed (keys key-preds)))}))))))

(s/fdef parse-settings
  :args (s/cat :text string?)
  :ret map?)

(defn- home-config-path []
  (str (System/getProperty "user.home") "/.config/cljtoc/config.edn"))

(defn default-config-path
  "Where load-settings looks when no path is given: ~/.config/cljtoc/config.edn."
  []
  (home-config-path))

(s/fdef default-config-path
  :args (s/cat)
  :ret string?)

(defn load-settings
  "Load settings from path (or the default location when nil). A missing
   file means defaults; a present file parses strictly. Returns
   {:ok settings} or {:error :invalid-config :message msg}."
  ([]
   (load-settings nil))
  ([path]
   (let [resolved (or path (default-config-path))
         file (io/file resolved)]
     (if-not (.exists file)
       {:ok default-settings}
       (try
         (parse-settings (slurp file))
         (catch Exception exception
           (config-error (str "Could not read config file " resolved
                              ": " (.getMessage exception)))))))))

(s/fdef load-settings
  :args (s/alt :bare (s/cat) :with-path (s/cat :path (s/nilable string?)))
  :ret map?)
