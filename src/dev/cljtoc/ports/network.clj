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

(def timeout-opt-keys
  "Adapter config keys holding socket timeouts in milliseconds."
  [:connect-timeout-ms :socket-timeout-ms :udp-timeout-ms :http-timeout-ms])

(defn check-timeout-opts
  "Validate present timeout opts before any network I/O: each must be a
   positive int within the Java int range the socket APIs take. Zero means
   infinite to Socket.connect/setSoTimeout, so present-but-invalid values
   throw instead of falling back to a default. Absent keys are fine (the
   historical defaults apply at use). Returns config unchanged."
  [config]
  (doseq [timeout-key timeout-opt-keys
          :when (contains? config timeout-key)
          :let [value (get config timeout-key)]]
    (when-not (and (pos-int? value) (<= value Integer/MAX_VALUE))
      (throw (ex-info (str "Invalid network timeout " timeout-key
                           ": expected positive int ms within Java int range, got "
                           (pr-str value))
                      {:key timeout-key :value value}))))
  config)

(s/fdef check-timeout-opts
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

  (announce [this torrent-metadata progress]
    "Announce to the tracker and get a list of peers.
     progress is {:downloaded bytes-on-disk :left bytes-remaining}: the
     tracker's leecher accounting depends on it, so a resume must report
     verified bytes rather than zero.
     Returns {:ok #{peer-address}} or {:error reason :message msg}."))
