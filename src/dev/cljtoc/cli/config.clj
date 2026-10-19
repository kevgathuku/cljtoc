(ns dev.cljtoc.cli.config
  "Client settings file: Transmission settings.json equivalent, EDN edition.

   Every path and limit the client runs with lives here as data. The file
   carries only keys with live consumers today (dirs, the announce port,
   the per-torrent peer cap); future slices add their own keys rather than
   this namespace guessing at them. Unknown keys warn and are dropped, so
   a file written for a newer client still loads.

   Precedence, weakest first: built-in defaults, the settings file, then
   the CLI invocation (positional args like output-dir). A missing file
   means defaults; a present-but-unreadable file is an error, never
   silent defaults over a typo'd file."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [dev.cljtoc.orchestration.download :as download]))

(def ^:private home-dir
  (System/getProperty "user.home"))

(def config-home
  "Where cljtoc reads config from. One derivation, shared by the default
   config path and the state dir: two derivations disagreed under
   XDG_CONFIG_HOME, moving the state without moving the file."
  (or (System/getenv "XDG_CONFIG_HOME") (str home-dir "/.config")))

(def cache-home
  (or (System/getenv "XDG_CACHE_HOME") (str home-dir "/.cache")))

(def default-settings
  "The knobs a user can actually change, with Transmission's values: port
   51413 (TrDefaultPeerPort), 50 peers per torrent
   (TrDefaultPeerLimitTorrent), downloads in ~/Downloads
   (tr_getDefaultDownloadDir), resume state under the config home, the
   piece cache under the cache home. Knobs the manager reserves but does
   not enforce (:min-peers, :piece-timeout-ms, and friends) stay in
   orchestration.download/default-config instead of being offered here
   as settings that do nothing."
  {:state-dir (str config-home "/cljtoc/state")
   :piece-cache-dir (str cache-home "/cljtoc")
   :output-dir (str home-dir "/Downloads")
   :peer-port 51413
   :max-peers (:max-peers download/default-config)})

(def ^:private key-preds
  "Validation per known key. Unknown keys never reach here: parse
   selects the known keys first, warning about the rest."
  {:state-dir string?
   :piece-cache-dir string?
   :output-dir string?
   :peer-port #(and (pos-int? %) (<= % 65535))
   :max-peers pos-int?})

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
                             (str "Invalid config value for " key-name
                                  ": " (pr-str value)))))))
             nil
             known))

(defn parse-settings
  "Parse EDN text into a settings map over the defaults. Unknown keys warn
   on stdout and are dropped. Returns {:ok settings} or
   {:error :invalid-config :message msg}.

   The read result is wrapped in {:ok _} rather than inspected directly:
   a document carrying its own :error key must not be mistaken for a
   failed parse, which is what the unwrapped form did."
  [text]
  (let [read-result (try
                      {:ok (edn/read-string text)}
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
                            (pr-str (sort (keys unknown))))))
            (or (validate-known known)
                {:ok (merge default-settings known)})))))))

(s/fdef parse-settings
  :args (s/cat :text string?)
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
  :ret ::settings-result)
