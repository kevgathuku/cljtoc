(ns dev.cljtoc.orchestration.coordinator
  "Pure coordinator for one active download.

   The loop threads one state map:
     {:download ... :active-peers ... :blocks-received ... :expected-blocks ...
      :pending-dials #{...}}
   :pending-dials holds dialed addresses with no resolved
   :peer-connected/:peer-disconnected yet (issue #11).
   Handlers take that map plus an event and return [new-state effects];
   channel I/O (sends, disk writes) happens at the loop edge in
   dev.cljtoc.orchestration.download.

   Zero port requires by design: pure domain + protocol only, so this
   namespace stays testable without test doubles."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]))

(defn initial-coordinator-state
  "The coordinator state for a download about to dial peer-addresses.
    Every dialed address starts pending; handlers resolve addresses out
    as :peer-connected/:peer-disconnected events arrive. Pure."
  [download peer-addresses]
  {:download download
   :active-peers {}
   :blocks-received {}
   :expected-blocks {}
   :pending-dials (set peer-addresses)})

(s/fdef initial-coordinator-state
  :args (s/cat :download map? :peer-addresses coll?)
  :ret map?)

(defn requeue-assignment
  "Requeue address's assigned piece and clear its bookkeeping.
   Best-effort on the piece state (a piece that already left in-flight
   stays where it is); the assignment and buffered blocks are always
   cleared. Returns the updated coordinator state map."
  [state address piece-idx]
  (let [download (:download state)
        requeued (pieces/requeue-piece (:piece-state download) piece-idx)]
    (-> state
        (assoc :download (if (:ok requeued)
                           (assoc download :piece-state (:ok requeued))
                           download))
        (assoc-in [:active-peers address :assigned-piece] nil)
        (update :blocks-received dissoc address))))

(s/fdef requeue-assignment
  :args (s/cat :state map? :address any? :piece-idx nat-int?)
  :ret map?)

(defn- resolve-dial
  "Drop address from :pending-dials: its dial resolved, whether by
   connecting or by failing. Idempotent — repeats and unknown
   addresses are no-ops, matching attempted-minus-resolved exactly."
  [state address]
  (update state :pending-dials (fnil disj #{}) address))

(defn on-connected
  "Record a newly connected peer with no assignment yet.
    The address resolves its dial, so it leaves :pending-dials too.
    Pure. Returns [updated-state effects] (a connection plans no I/O)."
  [state event]
  (let [{:keys [address peer-data peer-state]} event]
    [(-> state
         (assoc-in [:active-peers address]
                   {:peer-data peer-data
                    :peer-state peer-state
                    :assigned-piece nil})
         (resolve-dial address))
     []]))

(s/fdef on-connected
  :args (s/cat :state map? :event map?)
  :ret vector?)

(defn swarm-exhausted?
  "True when the swarm can no longer make progress: no active peers,
   no dials still in flight, and pieces still incomplete.
   :pending-dials is the set of dialed addresses with no resolved
   :peer-connected/:peer-disconnected yet (issue #11); a missing key
   counts as none pending. The caller fails the download."
  [state]
  (and (empty? (:active-peers state))
       (empty? (:pending-dials state))
       (not (pieces/complete? (get-in state [:download :piece-state])))))

(s/fdef swarm-exhausted?
  :args (s/cat :state map?)
  :ret boolean?)

(defn on-disconnected
  "Drop a peer, requeueing its assigned piece if any. Pure.
    Any resolved address leaves :pending-dials (attempted minus
    resolved): a refused dial was still pending, a connected peer
    resolved at connect time, and a repeat event is a no-op.
    Returns [updated-state effects] (a disconnect plans no I/O);
    the caller checks swarm-exhausted? to decide on failure."
  [state event]
  (let [{:keys [address]} event
        assigned (get-in state [:active-peers address :assigned-piece])
        state (if assigned
                (requeue-assignment state address assigned)
                state)]
    [(-> state
         (update :active-peers dissoc address)
         (update :blocks-received dissoc address)
         (update :expected-blocks dissoc address)
         (resolve-dial address))
     []]))

(s/fdef on-disconnected
  :args (s/cat :state map? :event map?)
  :ret vector?)

(defn- all-peer-available-sets
  "Get a collection of available-piece-sets from all active peers."
  [active-peers]
  (map (fn [[_ peer-info]]
         (peer-state/available-pieces (:peer-state peer-info)))
       active-peers))

(defn- requestable?
  "True when the peer may be given a piece: unchoked, interested, idle."
  [state address]
  (let [peer-info (get (:active-peers state) address)]
    (and (peer-state/can-request? (:peer-state peer-info))
         (nil? (:assigned-piece peer-info)))))

(defn- plan-request
  "Select a piece and plan its block requests without touching the network.
   ctx carries :piece-length and :total-length from the torrent info.
   Returns [new-state sends] or nil when nothing can be requested.
   Each send is {:peer-data ... :bytes ...} for the loop edge to deliver."
  [state address ctx]
  (let [download (:download state)
        active-peers (:active-peers state)
        peer-info (get active-peers address)
        piece-state (:piece-state download)
        select-result (pieces/select-piece piece-state
                                           (peer-state/available-pieces (:peer-state peer-info))
                                           (all-peer-available-sets active-peers))]
    (when-let [piece-idx (:ok select-result)]
      (let [mark-result (pieces/mark-in-flight piece-state piece-idx)]
        (when (:ok mark-result)
          (let [blocks-result (pieces/piece-blocks piece-idx
                                                   (:piece-length ctx)
                                                   (:total-length ctx))]
            (when (:ok blocks-result)
              (let [blocks (:ok blocks-result)
                    peer-data (:peer-data peer-info)
                    sends (vec (keep (fn [block]
                                       (let [req-msg (peer/->Request (:piece-index block)
                                                                     (:offset block)
                                                                     (:length block))]
                                         (when-let [req-bytes (:ok (peer/build-message req-msg))]
                                           {:address address
                                            :peer-data peer-data
                                            :bytes req-bytes})))
                                     blocks))]
                [(assoc state
                        :download (assoc download :piece-state (:ok mark-result))
                        :active-peers (assoc-in active-peers [address :assigned-piece] piece-idx)
                        :expected-blocks (assoc (:expected-blocks state) address (count blocks)))
                 sends]))))))))

(defn- maybe-request
  "The single 'after state update, maybe request' step: plan a request
   when the peer is requestable. Returns [new-state send-effects]."
  [state address ctx]
  (if (requestable? state address)
    (if-let [[planned sends] (plan-request state address ctx)]
      [planned (mapv (fn [send] {:send send}) sends)]
      [state []])
    [state []]))

(defn- assemble-and-verify
  "Assemble buffered blocks and verify the hash, planning the disk write.
   Marking waits for the write: the piece stays in-flight in the planned
   state and the edge marks it verified only after the bytes land.
   Returns [new-state effects verified?]: verified? is false on
   assembly/verification failure (the piece is requeued with no effects).
   The caller decides on the follow-up request via the single tail."
  [state address piece-idx addr-blocks ctx]
  (let [{:keys [piece-length total-length piece-hashes]} ctx
        piece-len (pieces/piece-length piece-idx piece-length total-length)]
    (if-let [assembled (:ok (pieces/assemble-piece addr-blocks piece-len))]
      (if (:ok (pieces/verify-piece piece-idx assembled (nth piece-hashes piece-idx)))
        [(-> state
             (assoc-in [:active-peers address :assigned-piece] nil)
             (update :blocks-received dissoc address))
         [{:write-verified {:piece-idx piece-idx :data assembled}}]
         true]
        [(requeue-assignment state address piece-idx) [] false])
      [(requeue-assignment state address piece-idx) [] false])))

(defn- handle-piece-message
  "Accumulate one Piece message; assemble on the final block.
   Returns [new-state effects may-request?]: may-request? is false when
   the piece just failed (assembly/verify) so the peer idles until its
   next message instead of hot-looping requests at a corrupt peer."
  [state address message ctx]
  (let [piece-idx (:piece-index message)
        assigned (get-in state [:active-peers address :assigned-piece])]
    (if (not= piece-idx assigned)
      [state [] true]
      (let [block {:offset (:begin message) :data (:data message)}
            addr-blocks (conj (get (:blocks-received state) address []) block)
            state (assoc-in state [:blocks-received address] addr-blocks)
            expected (get (:expected-blocks state) address 0)]
        (if (< (count addr-blocks) expected)
          [state [] true]
          (assemble-and-verify state address piece-idx addr-blocks ctx))))))

(defn on-message
  "Handle one peer message. Pure planning: takes [state event ctx] and
   returns [new-state effects]. ctx carries :piece-hashes, :piece-length,
   :total-length and :total-pieces from the torrent info.
   Effects are data for the loop edge:
     {:send {:peer-data ... :bytes ...}}
     {:write-verified {:piece-idx ... :data ...}}
   Requests go out through the single maybe-request step at the bottom."
  [state event ctx]
  (let [{:keys [address message]} event
        peer-info (get (:active-peers state) address)]
    (if (nil? peer-info)
      [state []]
      (let [ps (peer-state/apply-message (:peer-state peer-info) message)
            state (assoc-in state [:active-peers address :peer-state] ps)
            assigned (get-in state [:active-peers address :assigned-piece])
            [updated effects may-request?]
            (cond
              (instance? dev.cljtoc.protocol.peer.Choke message)
              [(if assigned
                 (requeue-assignment state address assigned)
                 state)
               [] false]

              (instance? dev.cljtoc.protocol.peer.Piece message)
              (handle-piece-message state address message ctx)

              (or (instance? dev.cljtoc.protocol.peer.Unchoke message)
                  (instance? dev.cljtoc.protocol.peer.Have message)
                  (instance? dev.cljtoc.protocol.peer.Bitfield message))
              [state [] true]

              :else
              [state [] false])]
        (if may-request?
          (let [[planned send-effects] (maybe-request updated address ctx)]
            [planned (into effects send-effects)])
          [updated effects])))))

(s/fdef on-message
  :args (s/cat :state map? :event map? :ctx map?)
  :ret vector?)
