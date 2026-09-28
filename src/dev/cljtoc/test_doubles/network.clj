(ns dev.cljtoc.test-doubles.network
  "Mock network port for testing download orchestration.
   
   Provides predictable responses for testing without actual network I/O."
  (:require [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.protocol.tracker :as tracker])
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
    (when-let [on-send (:on-send config)]
      (on-send peer message))
    (get-in @responses [(:id peer) (:type message)] {:ok :sent}))

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
  (announce-to-url [_ tracker-url _request]
    (when-let [capture (:announce-to-url-capture config)]
      (swap! capture conj tracker-url))
    (if-let [global-error (:announce-to-url-error config)]
      global-error
      (get (:announce-to-url-responses config) tracker-url
           {:ok (set (get config :mock-peers ["127.0.0.1:6881" "127.0.0.1:6882"]))})))

  (announce [this torrent-metadata progress]
    (when-let [capture (:announce-capture config)]
      (reset! capture {:torrent torrent-metadata :progress progress}))
    (if-let [announce-error (:announce-error config)]
      announce-error
      (let [tracker-urls (tracker/pick-tracker-order torrent-metadata)]
        (if (empty? tracker-urls)
          {:error :no-tracker :message "No tracker URL available"}
          (loop [urls tracker-urls
                 all-peers #{}
                 last-error nil]
            (if (empty? urls)
              (if (empty? all-peers)
                (or last-error
                    {:error :all-trackers-failed :message "All trackers failed"})
                {:ok all-peers})
              (let [result (network/announce-to-url this (first urls) nil)]
                (if (:ok result)
                  (recur (rest urls) (tracker/combine-peers all-peers (:ok result)) last-error)
                  (recur (rest urls) all-peers result))))))))))

(defn create
  "Create a mock network port for testing.
   
   Options:
   - :default-bitfield - set of piece indices this mock peer has (default: #{0 1 2 3 4})
   - :mock-peers - vector of peer addresses returned per tracker URL
     (combined across URLs into a set, mirroring the real port)
   - :announce-to-url-responses - {tracker-url result} per-URL scripts,
     overriding :mock-peers for that URL only
   - :announce-to-url-error - error map every per-URL query returns
   - :announce-to-url-capture - atom conjed with each queried URL, in order
   - :announce-error - error map to return from announce instead of peers
   - :announce-capture - atom reset to {:torrent _ :progress _} on announce,
     so tests can assert the reported downloaded/left
   - :handshake-response - map or (fn [peer]) returning {:ok handshake}
     or {:error ...} for receive-handshake
   - :receive-responses - atom holding a seq of {:ok ...} / {:error ...}
     returned one per receive-message call; falls back to keep-alive
     once the queue is empty
   - :on-send - (fn [peer message]) side effect run on every send-message
     call, so tests can observe effect timing (e.g. advance a mock clock)"
  ([]
   (create {}))
  ([config]
   (let [state (atom {:responses {}})
         closed (atom #{})]
     (->MockNetworkPort (network/check-adapter-config config) #{} state closed state))))

(defn add-peer-response [mock-network peer-id message-type response]
  (swap! (:responses mock-network) assoc-in [peer-id message-type] response))

(defn add-connected-peer [mock-network peer]
  (swap! (:connected-peers mock-network) conj peer))

(defn closed-peers
  "Peer-data maps the mock was asked to close."
  [mock-network]
  @(:closed-peers mock-network))
