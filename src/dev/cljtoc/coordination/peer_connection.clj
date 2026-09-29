(ns dev.cljtoc.coordination.peer-connection
  "One Peer's connection, whole: handshake, availability, choke/interest
   posture, and block traffic behind a single seam.

   Parsing, posture transitions, and verification live inside; callers
   see only connect, on-message, block-requests, and close.
   The transport is an INetworkPort: the real socket port in
   production, the in-memory mock in tests, so connections open with
   no network at all.

   The thread stays where it belongs: run-peer owns the worker thread
   and delegates each lifecycle step here. Nothing in this namespace
   touches core.async."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]
            [dev.cljtoc.utils :as utils]))

;; Length-exact hash/id specs single-source validation on protocol.peer
;; (::byte-array-20) and wrap feasible generation locally (repo
;; precedent: pieces.clj ::total-pieces — conformance unchanged,
;; generation only). The bare s/and bytes? + count form starves
;; such-that generation (proven red); without the wrapper the
;; verify-handshake pin cannot draw a single case.
(s/def ::info-hash
  (s/with-gen :dev.cljtoc.protocol.peer/info-hash
    #(utils/gen-byte-array 20)))
(s/def ::peer-id
  (s/with-gen :dev.cljtoc.protocol.peer/peer-id
    #(utils/gen-byte-array 20)))
(s/def ::peer-handshake
  (s/keys :req-un [::info-hash]))

(defn verify-handshake
  "Pure handshake verification: check the peer's handshake info-hash
   matches ours.
   Returns {:ok peer-handshake} or {:error :info-hash-mismatch}."
  [info-hash peer-handshake]
  (if (utils/bytes-equal? info-hash (:info-hash peer-handshake))
    {:ok peer-handshake}
    {:error :info-hash-mismatch}))

(s/fdef verify-handshake
  :args (s/cat :info-hash ::info-hash
               :peer-handshake ::peer-handshake)
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

(defn connect
  "Open a peer connection: dial, exchange handshakes, verify the
   peer's info-hash, send Interested, and return the ready posture.
   Blocking: the caller already sits on a worker thread.
   Returns {:ok {:peer-data _ :peer-state _}} or {:error reason ...} —
   never throws. A port that throws mid-handshake is closed and
   reported as {:error :handshake-failed ...}.
   A refused connection closes the socket before returning, so the
   caller only closes what connect handed back as :ok."
  [network-port info-hash our-peer-id address total-pieces]
  (let [connect-result (network/connect-peer network-port address)]
    (if (:error connect-result)
      connect-result
      (let [peer-data (:ok connect-result)]
        ;; An injected or alternate port may throw where the real one
        ;; returns an envelope. This fn keeps its contract — data, never
        ;; a throw — so close what the dial opened and fold the failure
        ;; into an envelope the worker already knows how to report.
        ;; Returned send-error envelopes stay fire-and-forget, exactly
        ;; as run-peer always treated them.
        (try
          (let [handshake-bytes (:ok (peer/build-handshake info-hash our-peer-id))]
            (network/send-message network-port peer-data handshake-bytes)
            (let [handshake-result (network/receive-handshake network-port peer-data)]
              (if (:error handshake-result)
                (do
                  (network/close-peer network-port peer-data)
                  handshake-result)
                (if (:error (verify-handshake info-hash (:ok handshake-result)))
                  (do
                    (network/close-peer network-port peer-data)
                    {:error :info-hash-mismatch :message "Info hash mismatch"})
                  (let [interested-bytes (:ok (peer/build-message (peer/->Interested)))]
                    (network/send-message network-port peer-data interested-bytes)
                    {:ok {:peer-data peer-data
                          :peer-state (initial-state total-pieces)}})))))
          (catch Exception thrown-error
            (network/close-peer network-port peer-data)
            {:error :handshake-failed :message (.getMessage thrown-error)}))))))

;; connect/close ride the INetworkPort seam, so stest/check cannot
;; conjure their port argument (same exclusion the fdef gate records
;; for every effect-port fn); example tests through MockNetworkPort
;; pin them instead.
(s/fdef connect
  :args (s/cat :network-port any?
               :info-hash ::info-hash
               :our-peer-id ::peer-id
               :address string?
               :total-pieces pos-int?)
  :ret map?)

(defn close
  "Release a connected peer through the port. Returns nil."
  [network-port peer-data]
  (network/close-peer network-port peer-data))

(s/fdef close
  :args (s/cat :network-port any? :peer-data map?)
  :ret nil?)

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

(defn block-requests
  "Block-request bytes for a piece's blocks over this connection.
   Pure: gates on posture, then builds one Request message per block.
   blocks are {:piece-index _ :offset _ :length _} maps as produced by
   pieces/piece-blocks. Returns {:ok [bytes]} or
   {:error :not-requestable} when the peer still chokes us."
  [connection-state blocks]
  (if-not (can-request? connection-state)
    {:error :not-requestable :message "Peer is choking or uninterested"}
    (reduce (fn [acc block]
              (if (:error acc)
                acc
                (let [built (peer/build-message
                             (peer/->Request (:piece-index block)
                                             (:offset block)
                                             (:length block)))]
                  (if (:error built)
                    built
                    {:ok (conj (:ok acc) (:ok built))}))))
            {:ok []}
            blocks)))

;; block-requests takes a PeerState (BitSet inside: un-generatable,
;; recorded at the generative pin in peer-connection-test), so
;; stest/check cannot run this fdef; the mutation tests pin it instead.
;; blocks are pieces/Block shapes (the ::block spec's only other home
;; is its own record in domain.pieces — referenced, not moved).
(s/fdef block-requests
  :args (s/cat :connection-state :dev.cljtoc.protocol.peer-state/peer-state
               :blocks (s/coll-of ::pieces/block))
  :ret map?)
