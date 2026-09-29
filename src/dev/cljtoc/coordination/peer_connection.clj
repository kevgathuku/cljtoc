(ns dev.cljtoc.coordination.peer-connection
  "One Peer's connection, whole: handshake, availability, choke/interest
   posture, and block traffic behind a single seam.

   Parsing, posture transitions, and verification live inside; callers
   see only connect, on-message, request (block-request bytes), and
   close. The transport is an INetworkPort: the real socket port in
   production, the in-memory mock in tests, so connections open with
   no network at all.

   The thread stays where it belongs: run-peer owns the worker thread
   and delegates each lifecycle step here. Nothing in this namespace
   touches core.async."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]))

(s/def ::info-hash bytes?)
(s/def ::peer-handshake (s/keys :req-un [::info-hash]))

(defn verify-handshake
  "Pure handshake verification: check the peer's handshake info-hash
   matches ours.
   Returns {:ok peer-handshake} or {:error :info-hash-mismatch}."
  [info-hash peer-handshake]
  (if (java.util.Arrays/equals ^bytes info-hash
                               ^bytes (:info-hash peer-handshake))
    {:ok peer-handshake}
    {:error :info-hash-mismatch}))

(s/fdef verify-handshake
  :args (s/cat :info-hash ::info-hash :peer-handshake ::peer-handshake)
  :ret map?)

(defn initial-state
  "Posture of a freshly connected peer: choked on both sides except
   our interest, which connect sends immediately, and no availability
   known until the first bitfield or have message lands."
  [total-pieces]
  (-> (peer-state/initial-peer-state total-pieces)
      (peer-state/set-am-interested true)))

(s/fdef initial-state
  :args (s/cat :total-pieces :dev.cljtoc.protocol.peer-state/total-pieces)
  :ret :dev.cljtoc.protocol.peer-state/peer-state
  :fn #(let [state (:ret %)]
         (and (true? (:am-choking state))
              (true? (:am-interested state))
              (true? (:peer-choking state))
              (false? (:peer-interested state))
              (nil? (:bitfield state))
              (= (-> % :args :total-pieces) (:total-pieces state)))))

(defn on-message
  "Fold one inbound wire message into the connection posture.
   Pure: (state, message) -> new-state. Data-plane traffic
   (KeepAlive, Request, Piece, Cancel) leaves posture untouched."
  [connection-state message]
  (peer-state/apply-message connection-state message))

(s/fdef on-message
  :args (s/cat :connection-state :dev.cljtoc.protocol.peer-state/peer-state
               :message any?)
  :ret :dev.cljtoc.protocol.peer-state/peer-state
  :fn #(= (-> % :args :connection-state :total-pieces)
          (-> % :ret :total-pieces)))

(defn can-request?
  "True when the connection may carry block traffic: the peer has
   unchoked us and we are interested."
  [connection-state]
  (peer-state/can-request? connection-state))

(s/fdef can-request?
  :args (s/cat :connection-state :dev.cljtoc.protocol.peer-state/peer-state)
  :ret boolean?
  :fn #(= (:ret %)
          (boolean (and (not (-> % :args :connection-state :peer-choking))
                        (-> % :args :connection-state :am-interested)))))
