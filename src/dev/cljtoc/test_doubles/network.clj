(ns dev.cljtoc.test-doubles.network
  "Mock network port for testing download orchestration.
   
   Provides predictable responses for testing without actual network I/O."
  (:require [dev.cljtoc.ports.network :as network])
  (:import [java.util UUID]))

(defrecord MockNetworkPort
           [config
            peers
            connected-peers
            closed-peers
            responses]

  network/INetworkPort
  (connect-peer [_ address]
    {:ok {:id (str address "/" (UUID/randomUUID))
          :address address
          :bitfield (:default-bitfield config)}})

  (send-message [_ peer message]
    (get-in @responses [(:id peer) (:type message)] {:ok :mock-response}))

  (receive-message [_ _]
    (or (when-let [receive-queue (:receive-responses config)]
          (let [[queued-responses _] (swap-vals! receive-queue rest)]
            (first queued-responses)))
        {:ok {:type :keep-alive}}))

  (receive-handshake [_ peer]
    (let [response (:handshake-response config)]
      (cond
        (fn? response) (response peer)
        (some? response) response
        :else {:ok {:info-hash (byte-array 20)
                    :peer-id (byte-array 20)}})))

  (close-peer [_ peer]
    (swap! closed-peers conj peer)
    nil)

  network/ITrackerPort
  (announce [_ _]
    (if-let [announce-error (:announce-error config)]
      announce-error
      {:ok (get config :mock-peers ["127.0.0.1:6881" "127.0.0.1:6882"])})))

(defn create
  "Create a mock network port for testing.
   
   Options:
   - :default-bitfield - set of piece indices this mock peer has (default: #{0 1 2 3 4})
   - :mock-peers - vector of peer addresses to return on announce
   - :announce-error - error map to return from announce instead of peers
   - :handshake-response - map or (fn [peer]) returning {:ok handshake}
     or {:error ...} for receive-handshake
   - :receive-responses - atom holding a seq of {:ok ...} / {:error ...}
     returned one per receive-message call; falls back to keep-alive
     once the queue is empty"
  ([]
   (create {}))
  ([config]
   (let [state (atom {:responses {}})
         closed (atom #{})]
     (->MockNetworkPort config #{} state closed state))))

(defn add-peer-response [mock-network peer-id message-type response]
  (swap! (:responses mock-network) assoc-in [peer-id message-type] response))

(defn add-connected-peer [mock-network peer]
  (swap! (:connected-peers mock-network) conj peer))

(defn closed-peers
  "Peer-data maps the mock was asked to close."
  [mock-network]
  @(:closed-peers mock-network))
