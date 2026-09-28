(ns dev.cljtoc.ports.network
  "Network port protocol for peer communication effects.
  
   This protocol defines the contract for all network I/O operations
   needed by the download orchestration layer. Implementations can be
   swapped for testing (test doubles) or different network backends.

   Every method returns its result directly, by return value, and every
   method blocks until it has one. Nothing here runs work on a pool, so a
   caller that wants two calls in flight has to put them on threads of its
   own -- that choice belongs above this seam, not inside it."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker]))

(defn log-fn
  "Return the port's configured log fn (a fn of one message string).
   Both the real NetworkPort and the mock carry their adapter config in
   :config; anything else logs via println. Keeps println side effects
   injectable without changing call shapes."
  [port]
  (get (:config port) :log-fn println))

(s/fdef log-fn
  :args (s/cat :port any?)
  :ret fn?)

(defn log!
  "Log a message through the adapter config. Best-effort by design: a
   throwing logger is swallowed (nil) so observability can never break
   the effect path -- e.g. aborting tracker fallback or crashing a peer
   worker. Mirrors close-peer's swallow precedent."
  [port message]
  (try
    ((log-fn port) message)
    nil
    (catch Exception _ nil)))

;; Excluded from stest/check: side-effecting by design (println default)
;; and dispatches to an injected fn, so generated cases would print noise.
(s/fdef log!
  :args (s/cat :port any? :message string?)
  :ret nil?)

(s/def ::timeout-ms (s/and pos-int? #(<= % Integer/MAX_VALUE)))
(s/def ::connect-timeout-ms ::timeout-ms)
(s/def ::socket-timeout-ms ::timeout-ms)
(s/def ::udp-timeout-ms ::timeout-ms)
(s/def ::http-timeout-ms ::timeout-ms)
;; fn? has no generator, so the bare predicate would make the shape
;; un-generatable (s/gen fails at :log-fn); generate println instead,
;; mirroring tracker/spec.clj's with-gen precedent.
(s/def ::log-fn (s/with-gen fn? #(gen/return println)))
(s/def ::adapter-config
  (s/keys :opt-un [::connect-timeout-ms ::socket-timeout-ms
                   ::udp-timeout-ms ::http-timeout-ms ::log-fn]))

(def timeout-opt-keys
  "Adapter config keys holding socket timeouts in milliseconds."
  [:connect-timeout-ms :socket-timeout-ms :udp-timeout-ms :http-timeout-ms])

(defn check-adapter-config
  "Validate adapter opts before any network I/O: present timeouts must be
   positive ints within the Java int range the socket APIs take (zero means
   infinite, so present-but-invalid values throw instead of falling back),
   and a present :log-fn must be a fn. Absent keys are fine (historical
   defaults apply at use). Returns config unchanged."
  [config]
  (doseq [timeout-key timeout-opt-keys
          :when (contains? config timeout-key)
          :let [value (get config timeout-key)]]
    (when-not (and (pos-int? value) (<= value Integer/MAX_VALUE))
      (throw (ex-info (str "Invalid network adapter opt " timeout-key
                           ": expected positive int ms within Java int range, got "
                           (pr-str value))
                      {:key timeout-key :value value}))))
  (when (contains? config :log-fn)
    (let [log-fn-value (:log-fn config)]
      (when-not (fn? log-fn-value)
        (throw (ex-info (str "Invalid network adapter opt :log-fn"
                             ": expected a fn of one message string, got "
                             (pr-str log-fn-value))
                        {:key :log-fn :value log-fn-value})))))
  config)

(s/fdef check-adapter-config
  :args (s/cat :config map?)
  :ret map?)

(defprotocol INetworkPort
  "Abstraction for network operations needed by download orchestration."

  (connect-peer [this address]
    "Open TCP connection to a peer at the given address.
     Returns {:ok peer-data} or {:error reason :message msg}.")

  (send-message [this peer message]
    "Send a peer wire message to the connected peer.
     Returns {:ok :sent} or {:error reason :message msg}.")

  (receive-message [this peer]
    "Receive the next message from a peer.
     Returns {:ok peer-message} or {:error reason :message msg}.")

  (receive-handshake [this peer]
    "Read a 68-byte peer handshake from the connection.
     Returns {:ok peer-handshake} or {:error reason :message msg}.")

  (close-peer [this peer]
    "Close the connection to a peer gracefully.
     Returns nil."))

(defprotocol ITrackerPort
  "Abstraction for tracker communication operations."

  (announce-to-url [this tracker-url request]
    "Announce to ONE tracker URL and get its peers.
     request is the announce payload (:info-hash :peer-id :port
     :uploaded :downloaded :left, plus optional :event :compact
     :num-want): built once by the caller, reused per URL so a
     coordinator loop can query URLs incrementally and emit
     :tracker-peers as responses land.
     Returns {:ok #{peer-address}} or {:error reason :message msg}.")

  (announce [this torrent-metadata progress]
    "Announce to the tracker and get a list of peers.
     progress is {:downloaded bytes-on-disk :left bytes-remaining}: the
     tracker's leecher accounting depends on it, so a resume must report
     verified bytes rather than zero.
     Returns {:ok #{peer-address}} or {:error reason :message msg}."))
