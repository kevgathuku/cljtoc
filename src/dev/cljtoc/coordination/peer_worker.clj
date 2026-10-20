(ns dev.cljtoc.coordination.peer-worker
  "Per-peer connection lifecycle management.

   Handles connecting to a peer, performing the BitTorrent handshake,
   sending Interested, and entering a read loop that forwards all
   received messages onto a shared events channel.

   Uses async/thread for blocking socket reads."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [dev.cljtoc.coordination.peer-connection :as peer-connection]
            [dev.cljtoc.ports.network :as net]))

(defn- report!
  "Put a peer event on the channel. Returns false when the channel is
   closed: the peer supervisor stopped, so the worker reports nothing
   more and closes its socket. A put released by close! returns nil,
   which is false too."
  [events-ch event]
  (async/>!! events-ch event))

(defn run-peer
  "Connect to peer, perform handshake, then enter read loop.
   All events are put onto events-ch for the coordinator.

   Parameters:
     network-port - NetworkPort instance
     info-hash    - 20-byte info hash for handshake verification
     our-peer-id  - 20-byte peer ID for our handshake
     address      - \"host:port\" string
     total-pieces - number of pieces in the torrent
     events-ch    - shared channel for coordinator events

   Returns a channel that yields the worker's exit outcome: {:exit :clean}
   when the peer disconnected or the handshake failed, and {:exit :crash
   :error e} when the worker hit an unexpected failure. The peer
   supervisor reads that outcome to decide between a restart and a drop,
   so a crashed worker leaves the disconnect to the supervisor: only the
   supervisor knows whether the address will be dialed again."
  [network-port info-hash our-peer-id address total-pieces events-ch]
  (net/log! network-port (str "[run-peer] Starting peer worker for address: " address))
  (async/thread
    (try
      ;; Connect owns dial, handshake, verify, and Interested in one
      ;; seam (peer-connection/connect); the worker only carries events.
      (let [connect-result (peer-connection/connect
                            network-port info-hash our-peer-id address total-pieces)]
        (if (:error connect-result)
          (do
            (async/>!! events-ch {:type :peer-disconnected
                                  :address address
                                  :reason (:message connect-result)})
            {:exit :clean})
          (let [{:keys [peer-data peer-state]} (:ok connect-result)]
            ;; A closed channel means the supervisor stopped: nobody
            ;; listens, so the worker closes its socket and goes.
            (if-not (report! events-ch {:type :peer-connected
                                        :address address
                                        :peer-data peer-data
                                        :peer-state peer-state})
              (do (peer-connection/close network-port peer-data)
                  {:exit :clean})
              (try
                ;; Enter read loop
                (loop []
                  (let [msg-result (net/receive-message network-port peer-data)]
                    (cond
                      (or (nil? msg-result) (:error msg-result))
                      (do
                        (peer-connection/close network-port peer-data)
                        (report! events-ch {:type :peer-disconnected
                                            :address address
                                            :reason (or (:message msg-result) "connection closed")})
                        {:exit :clean})

                      (report! events-ch {:type :peer-message
                                          :address address
                                          :message (:ok msg-result)})
                      (recur)

                      :else
                      (do (peer-connection/close network-port peer-data)
                          {:exit :clean}))))
                (catch Exception e
                  ;; An unexpected failure: close the connection and report
                  ;; the crash. The supervisor decides whether the worker is
                  ;; restarted, and it is the one that tells the coordinator
                  ;; the peer is gone.
                  (peer-connection/close network-port peer-data)
                  {:exit :crash :error e}))))))
      (catch Exception e
        {:exit :crash :error e}))))

;; Excluded from stest/check: the worker rides the network port and a
;; channel, so no generator can conjure either.
(s/fdef run-peer
  :args (s/cat :network-port any?
               :info-hash any?
               :our-peer-id any?
               :address string?
               :total-pieces pos-int?
               :events-ch any?)
  :ret any?)
