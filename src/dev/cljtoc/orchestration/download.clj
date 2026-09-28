(ns dev.cljtoc.orchestration.download
  "Download orchestration - coordinates torrent downloads.

   This namespace manages the end-to-end download process:
   - Parsing .torrent files
   - Connecting to trackers to get peers
   - Managing peer connections
   - Selecting pieces using rarest-first
   - Downloading and verifying pieces
   - Writing verified pieces to disk

   All I/O is performed through injected port protocols, making this
   code testable with mock implementations."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.domain.peer-address :as peer-address]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.time :as time]
            [dev.cljtoc.coordination.peer-worker :as peer-worker])
  (:import [java.security SecureRandom]))

(defrecord Download
           [id
            torrent
            piece-state
            peers
            state
            output-dir
            stats
            error])

(defrecord Peer
           [id
            address
            port
            bitfield
            am-choking
            am-interested
            peer-choking
            peer-interested
            downloaded
            uploaded])

(defrecord DownloadStats
           [started-at
            completed-at
            bytes-downloaded
            bytes-uploaded
            last-update])

(defrecord ErrorInfo
           [reason
            message
            failed-piece])

;; ============================================================================
;; Specs
;; ============================================================================

(s/def ::download-id string?)

(s/def ::state keyword?)

(s/def ::output-dir string?)

(s/def ::bytes-downloaded nat-int?)

(s/def ::pieces-complete nat-int?)

(s/def ::pieces-total nat-int?)

(s/def ::peers-connected nat-int?)

(s/def ::rate-bytes-per-sec nat-int?)

(s/def ::percent number?)

(s/def ::download-state #{:idle :starting :downloading :paused :completed :failed})

(s/def ::progress-response
  (s/keys :req-un [::percent
                   ::pieces-complete
                   ::pieces-total
                   ::bytes-downloaded
                   ::rate-bytes-per-sec
                   ::peers-connected
                   ::state]))

(s/def ::max-peers pos-int?)

(s/def ::min-peers nat-int?)

(s/def ::request-queue-size nat-int?)

(s/def ::piece-timeout-ms nat-int?)

(s/def ::tracker-announce-interval-ms nat-int?)

(s/def ::config
  (s/keys :opt-un [::max-peers
                   ::min-peers
                   ::request-queue-size
                   ::piece-timeout-ms
                   ::tracker-announce-interval-ms
                   ::output-dir]))

(s/def ::network-port any?)

(s/def ::disk-port any?)

(s/def ::time-port any?)

(s/def ::downloads map?)

(s/def ::download-manager
  (s/keys :req-un [::network-port ::disk-port ::time-port ::downloads ::config]))

(def valid-states #{:idle :starting :downloading :paused :completed :failed})

(def default-config
  "Default limits for downloads. Only :max-peers is currently enforced
   (see capped-peer-addresses); the rest are reserved for future use."
  {:max-peers 50
   :min-peers 5
   :request-queue-size 16
   :piece-timeout-ms 30000
   :tracker-announce-interval-ms 1800000
   :output-dir "./downloads"})

;; ============================================================================
;; Download Manager
;; ============================================================================

(defrecord DownloadManager
           [network-port
            disk-port
            time-port
            downloads
            config])

(defn manager
  "Create a DownloadManager with the given port implementations and config.

   Parameters:
   - network-port: implementation of INetworkPort
   - disk-port: implementation of IDiskPort
   - time-port: implementation of ITimePort
   - config: optional map of configuration overrides (validated)

   Returns a DownloadManager record."
  ([network-port disk-port time-port]
   (manager network-port disk-port time-port {}))
  ([network-port disk-port time-port config]
   (let [merged (merge default-config config)]
     (when-not (s/valid? ::config merged)
       (throw (ex-info "Invalid manager config"
                       (s/explain-data ::config merged))))
     (->DownloadManager network-port
                        disk-port
                        time-port
                        {}
                        merged))))

(s/fdef manager
  :args (s/cat :network-port any?
               :disk-port any?
               :time-port any?
               :config (s/? ::config))
  :ret ::download-manager)

(defn add-download [manager download]
  (update manager :downloads assoc (:id download) download))

(s/fdef add-download
  :args (s/cat :manager ::download-manager :download map?)
  :ret ::download-manager)

(defn get-download [manager id]
  (get (:downloads manager) id))

(s/fdef get-download
  :args (s/cat :manager ::download-manager :id any?)
  :ret (s/or :download map? :nil nil?))

(defn remove-download [manager id]
  (update manager :downloads dissoc id))

(s/fdef remove-download
  :args (s/cat :manager ::download-manager :id any?)
  :ret ::download-manager)

(defn- download-error
  [reason message & [failed-piece]]
  {:error reason :message message :failed-piece failed-piece})

(defn- parse-torrent [disk-port torrent-path]
  (let [result (disk/read-torrent-file disk-port torrent-path)]
    (if (:error result)
      (download-error :invalid-torrent (:message result))
      {:ok (:ok result)})))

(defn- announce-to-tracker [network-port torrent]
  (let [result (network/announce network-port torrent)]
    (if (:error result)
      (download-error :tracker-error (:message result))
      {:ok (:ok result)})))

(defn initial-stats [time-port]
  (let [now (time/now time-port)]
    (->DownloadStats now nil 0 0 now)))

(s/fdef initial-stats
  :args (s/cat :time-port any?)
  :ret (s/keys :req-un [::started-at]))

(defn update-stats-bytes [time-port stats bytes-received]
  (let [now (time/now time-port)
        prev-bytes (:bytes-downloaded stats)
        prev-time (:last-update stats)
        elapsed-seconds (max 1 (/ (- now prev-time) 1000.0))
        new-total (+ prev-bytes bytes-received)
        rate (long (/ bytes-received elapsed-seconds))]
    (-> stats
        (assoc :bytes-downloaded new-total)
        (assoc :last-update now)
        (assoc :rate rate))))

(s/fdef update-stats-bytes
  :args (s/cat :time-port any? :stats map? :bytes-received nat-int?)
  :ret map?)

(defn calculate-rate [time-port stats]
  (let [now (time/now time-port)
        elapsed-seconds (/ (- now (:last-update stats)) 1000.0)
        bytes-downloaded (:bytes-downloaded stats)]
    (if (and (> elapsed-seconds 0) (> bytes-downloaded 0))
      (long (/ bytes-downloaded elapsed-seconds))
      0)))

(s/fdef calculate-rate
  :args (s/cat :time-port any? :stats map?)
  :ret nat-int?)

(defn initial-download [time-port torrent output-dir download-id]
  (let [info (:info torrent)
        total-pieces (count (:pieces info))
        piece-state (pieces/initial-piece-state total-pieces)]
    (->Download download-id
                torrent
                piece-state
                #{}
                :starting
                output-dir
                (initial-stats time-port)
                nil)))

(s/fdef initial-download
  :args (s/cat :time-port any? :torrent map? :output-dir string? :download-id string?)
  :ret (s/keys :req-un [::download-id ::state]))

(defn capped-peer-addresses
  "Limit dial candidates to :max-peers, falling back to the
   default-config limit when the context carries no config."
  [peer-addresses config]
  (let [max-peers (or (:max-peers config) (:max-peers default-config))]
    (take max-peers peer-addresses)))

(s/fdef capped-peer-addresses
  :args (s/cat :peer-addresses coll? :config map?)
  :ret seq?)

(defn- build-peers
  "Build Peer records from announced address strings, skipping invalid ones."
  [announced-addresses]
  (set (keep (fn [address-str]
               (let [parsed (peer-address/parse address-str)]
                 (if (:error parsed)
                   (do
                     (println (str "[peers] WARNING: Skipping invalid peer address " address-str ": " (:message parsed)))
                     nil)
                   (let [{:keys [host port]} (:ok parsed)
                         canonical (peer-address/format-address (:ok parsed))
                         _ (println (str "[peers] Created peer: " address-str " -> host=" host " port=" port))]
                     (->Peer canonical canonical port #{} true false true false 0 0)))))
             announced-addresses)))

(defn start-download [manager torrent-path output-dir]
  (let [{:keys [network-port disk-port time-port config]} manager
        parse-result (parse-torrent disk-port torrent-path)]
    (if (:error parse-result)
      parse-result
      (let [torrent (:ok parse-result)
            download (initial-download time-port torrent output-dir (disk/id-from-path torrent-path))
            announce-result (announce-to-tracker network-port torrent)]
        (if (:error announce-result)
          (assoc download :state :failed
                 :error (:error announce-result)
                 :message (:message announce-result))
          (let [peers (:ok announce-result)
                _ (println (str "[start-download] Raw peer addresses sample: " (vec (take 5 peers))))]
            (assoc download
                   :state :downloading
                   :peers (set (capped-peer-addresses (build-peers peers) config)))))))))

(s/fdef start-download
  :args (s/cat :manager ::download-manager :torrent-path string? :output-dir string?)
  :ret map?)

(defn progress [time-port download]
  (let [piece-state (:piece-state download)
        total (pieces/verified-count piece-state)
        total-pieces (:total-pieces piece-state)
        percent (if (zero? total-pieces)
                  0.0
                  (* 100.0 (/ total total-pieces)))
        stats (:stats download)
        bytes-downloaded (:bytes-downloaded stats)
        rate (calculate-rate time-port stats)]
    {:percent percent
     :pieces-complete total
     :pieces-total total-pieces
     :bytes-downloaded bytes-downloaded
     :rate-bytes-per-sec rate
     :peers-connected (count (:peers download))
     :state (:state download)}))

(s/fdef progress
  :args (s/cat :time-port any? :download map?)
  :ret ::progress-response)

(defn pause-download
  "Pause an active download.
   - Closes all peer connections
   - Persists state to disk via IDiskPort
   - Returns updated download with :paused state"
  ([download]
   (pause-download nil download))
  ([disk-port download]
   (if (= :downloading (:state download))
     (let [paused-download (assoc download :state :paused :peers #{})]
       (if disk-port
         (let [save-result (disk/save-state disk-port paused-download)]
           (if (:error save-result)
             {:error (:error save-result) :message "Failed to persist state"}
             {:ok paused-download}))
         {:ok paused-download}))
     {:error :not-running :message "Download is not running"})))

(s/fdef pause-download
  :args (s/cat :disk-port (s/? any?) :download map?)
  :ret (s/or :ok (s/keys :req-un [::state])
             :error map?))

(def ^:private resumable-states
  "States a saved record can be revived from.

   :failed joins :paused because a record that died with the swarm
   exhausted is exactly the case with work left to do. :downloading is not
   one of them -- a record in that state is either this process's own run
   or a crash with no peers to reconcile -- and :completed has nothing
   left to fetch."
  #{:paused :failed})

(defn- requeue-stranded-pieces
  "Return piece-state with every in-flight piece back in :needed.

   A run that died mid-piece left that piece in flight: no peer is
   requesting it and nothing will verify it, so a resumed swarm would skip
   it forever and the download could never complete. Returns the updated
   piece state, leaving a piece that already moved alone."
  [piece-state]
  (reduce (fn [state piece-index]
            (let [requeued (pieces/requeue-piece state piece-index)]
              (if (:ok requeued) (:ok requeued) state)))
          piece-state
          (:in-flight piece-state)))

(defn resume-download
  "Resume a paused or failed download.

   - Loads persisted state from disk when a disk port is given
   - Re-announces to the tracker when a network port is given: the saved
     peer set is the one that just failed (pause clears it outright), and
     run-download derives every worker from :peers
   - Requeues pieces the dead run left in flight
   - Returns the download in :downloading state"
  ([download]
   (resume-download nil nil download))
  ([disk-port network-port download]
   (if (contains? resumable-states (:state download))
     (let [stored (when disk-port
                    (:ok (disk/load-state disk-port (:id download))))
           restored (or stored download)
           revived (assoc restored
                          :state :downloading
                          ;; Starts empty whatever the record carried. The
                          ;; announce below fills it in; without a network
                          ;; port it stays empty, so run-download refuses
                          ;; instead of redialing peers already known dead.
                          :peers #{})
           revived (assoc revived
                          :error nil
                          :piece-state (requeue-stranded-pieces
                                        (:piece-state revived)))]
       (if network-port
         (let [announce-result (announce-to-tracker network-port (:torrent revived))]
           (if (:error announce-result)
             announce-result
             {:ok (assoc revived :peers (build-peers (:ok announce-result)))}))
         {:ok revived}))
     {:error :not-paused
      :message (str "Download is not resumable from state "
                    (:state download))})))

(s/fdef resume-download
  :args (s/cat :disk-port (s/? any?) :network-port (s/? any?) :download map?)
  :ret (s/or :ok (s/keys :req-un [::state])
             :error map?))

(defn load-persisted-state
  "Load persisted download state from disk."
  [disk-port download-id]
  (if disk-port
    (disk/load-state disk-port download-id)
    nil))

(s/fdef load-persisted-state
  :args (s/cat :disk-port any? :download-id any?)
  :ret any?)

(defn persist-download-state
  "Persist current download state to disk for recovery."
  [disk-port download]
  (if disk-port
    (disk/save-state disk-port download)
    {:ok :no-disk-port}))

(s/fdef persist-download-state
  :args (s/cat :disk-port any? :download map?)
  :ret any?)

(defn stop-download [download]
  (assoc download :state :idle :peers #{}))

(s/fdef stop-download
  :args (s/cat :download map?)
  :ret (s/keys :req-un [::state ::peers]))

;; ============================================================================
;; Error Handling (User Story 3)
;; ============================================================================

(defn requeue-piece
  "Move a piece back to needed state for re-download.
   Returns updated download."
  [download piece-index]
  (let [piece-state (:piece-state download)
        result (pieces/requeue-piece piece-state piece-index)]
    (if (:error result)
      download
      (assoc download :piece-state (:ok result)))))

(s/fdef requeue-piece
  :args (s/cat :download map? :piece-index nat-int?)
  :ret map?)

(defn handle-piece-verification-failure
  "Handle piece verification failure by re-queuing the piece.
   Returns updated download with piece back in needed state."
  [download piece-index]
  (requeue-piece download piece-index))

(s/fdef handle-piece-verification-failure
  :args (s/cat :download map? :piece-index nat-int?)
  :ret map?)

(defn handle-peer-disconnect
  "Handle peer disconnection by removing peer and re-queueing in-flight pieces.
   Returns updated download."
  [download peer-id]
  (let [peer (first (filter #(= (:id %) peer-id) (:peers download)))
        in-flight-pieces (if peer (:in-flight (:piece-state download)) #{})
        download (update download :peers disj peer)]
    (reduce requeue-piece download in-flight-pieces)))

(s/fdef handle-peer-disconnect
  :args (s/cat :download map? :peer-id any?)
  :ret map?)

(defn add-peer
  "Add a new peer to the download.
   Returns updated download."
  [download peer]
  (update download :peers conj peer))

(s/fdef add-peer
  :args (s/cat :download map? :peer map?)
  :ret map?)

(defn remove-peer
  "Remove a peer from the download by ID.
   Returns updated download."
  [download peer-id]
  (let [peer (first (filter #(= (:id %) peer-id) (:peers download)))]
    (if peer
      (update download :peers disj peer)
      download)))

(s/fdef remove-peer
  :args (s/cat :download map? :peer-id any?)
  :ret map?)

(defn transition-to-failed
  "Transition download to failed state with error information.
   Returns updated download."
  [download error-info]
  (assoc download :state :failed :error error-info))

(s/fdef transition-to-failed
  :args (s/cat :download map? :error-info map?)
  :ret map?)

(defn can-retry?
  "Check if download can be retried (hasn't exceeded retry limit)."
  [download]
  (let [retry-count (or (get-in download [:error :retry-count]) 0)]
    (< retry-count 3)))

(s/fdef can-retry?
  :args (s/cat :download map?)
  :ret boolean?)

(defn retry-download
  "Retry a failed download by resetting state and clearing error."
  [download]
  (if (can-retry? download)
    (let [current-retry (or (get-in download [:error :retry-count]) 0)
          new-retry-count (inc current-retry)
          download (assoc download :state :starting :error {:retry-count new-retry-count})]
      download)
    {:error :max-retries-exceeded :message "Download has exceeded maximum retry attempts"}))

(s/fdef retry-download
  :args (s/cat :download map?)
  :ret map?)

(defn get-failed-piece
  "Get the piece index that failed, if any."
  [download]
  (get-in download [:error :failed-piece]))

(s/fdef get-failed-piece
  :args (s/cat :download map?)
  :ret any?)

(defn has-active-peers?
  "Check if download has any active peer connections."
  [download]
  (pos? (count (:peers download))))

(s/fdef has-active-peers?
  :args (s/cat :download map?)
  :ret boolean?)

(defn handle-no-peers
  "Handle the case when all peers disconnect.
   Returns updated download with appropriate state."
  [download]
  (if (pieces/complete? (:piece-state download))
    (assoc download :state :completed)
    (transition-to-failed download
                          {:reason :no-peers
                           :message "No peers available for download"
                           :failed-piece nil})))

(s/fdef handle-no-peers
  :args (s/cat :download map?)
  :ret map?)

;; ============================================================================
;; Active Download Coordinator
;; ============================================================================
;;
;; The loop threads one state map:
;;   {:download ... :active-peers ... :blocks-received ... :expected-blocks ...
;;    :pending-dials #{...}}
;; :pending-dials holds dialed addresses with no resolved
;; :peer-connected/:peer-disconnected yet (issue #11).
;; Handlers take that map plus an event and return [new-state effects];
;; channel I/O (sends, disk writes) happens at the loop edge.

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
  (let [{:keys [piece-length total-length total-pieces piece-hashes]} ctx
        piece-len (if (= piece-idx (dec total-pieces))
                    (- total-length (* piece-idx piece-length))
                    piece-length)]
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

(defn- fail-verified-write
  "Unwind a failed verified-piece write: requeue the write's piece plus
   every follow-up assignment this batch planned (their sends never ran),
   close every connection (a failed download must not leak workers), and
   fail so the record stays retryable. Returns [state {:fatal ...}]."
  [state effects piece-idx message network-port]
  (let [send-addrs (distinct (keep #(get-in % [:send :address]) effects))
        state (reduce (fn [unwound address]
                        (if-let [assigned (get-in unwound [:active-peers address :assigned-piece])]
                          (requeue-assignment unwound address assigned)
                          unwound))
                      state send-addrs)
        piece-state (get-in state [:download :piece-state])
        requeued (pieces/requeue-piece piece-state piece-idx)]
    (doseq [[_ peer-info] (:active-peers state)]
      (network/close-peer network-port (:peer-data peer-info)))
    [(-> state
         (assoc-in [:download :piece-state] (or (:ok requeued) piece-state))
         (update :expected-blocks #(apply dissoc % send-addrs)))
     {:fatal {:reason :disk-error
              :message (str "Failed to write piece " piece-idx ": " message)
              :failed-piece piece-idx}}]))

(defn- perform-effects!
  "Deliver planned effects at the loop edge: block-request sends go out
   over the network port, verified pieces go to the piece cache and the
   output file layout via the disk port with the download stats updated.
   Piece writes use the output layout compiled once per download and
   carried in env, never re-derived per piece. Returns [state outcome]:
   :ok, or {:fatal error-info} when a verified write failed and the
   download cannot honestly continue.

   A failed send treats the peer as disconnected: close its socket,
   requeue its piece and drop it from every bookkeeping map, so a dead
   peer never strands assignments or blocks exhaustion. Later sends in
   the same batch find no assignment and are skipped."
  [state effects env]
  (loop [state state
         [effect & rest-effects] effects]
    (if (nil? effect)
      [state :ok]
      (let [{:keys [ports conn-stats output-layout]} env
            {:keys [network-port disk-port time-port]} ports]
        (cond
          (:send effect)
          (let [{:keys [address peer-data bytes]} (:send effect)
                assigned (get-in state [:active-peers address :assigned-piece])]
            (if (nil? assigned)
              (recur state rest-effects)
              (let [result (network/send-message network-port peer-data bytes)]
                (if (:error result)
                  (do
                    (network/close-peer network-port peer-data)
                    (swap! conn-stats (fn [stats]
                                        (-> stats
                                            (update :failed inc)
                                            (update :failed-addresses (fnil conj #{}) address))))
                    (recur (-> state
                               (requeue-assignment address assigned)
                               (update :active-peers dissoc address)
                               (update :blocks-received dissoc address)
                               (update :expected-blocks dissoc address))
                           rest-effects))
                  (recur state rest-effects)))))

          (:write-verified effect)
          (let [{:keys [piece-idx data]} (:write-verified effect)
                download (:download state)
                result (disk/write-piece disk-port piece-idx data)]
            (if (:error result)
              ;; Bytes never landed in the cache: unwind and fail.
              (fail-verified-write state effects piece-idx (:message result) network-port)
              (let [output-result (disk/write-output-piece
                                   disk-port
                                   output-layout
                                   (:output-dir download)
                                   piece-idx data)]
                (if (:error output-result)
                  ;; Cache holds bytes the layout lacks: unwind and fail
                  ;; rather than verify air on resume.
                  (fail-verified-write state effects piece-idx (:message output-result) network-port)
                  (let [mark-result (pieces/mark-verified (get-in state [:download :piece-state]) piece-idx)
                        state (if (:ok mark-result)
                                (assoc-in state [:download :piece-state] (:ok mark-result))
                                state)]
                    (recur (update state :download
                                   (fn [download]
                                     (update download :stats
                                             #(update-stats-bytes time-port % (alength ^bytes data)))))
                           rest-effects))))))

          :else
          (recur state rest-effects))))))

(defn- format-bytes-rate [bytes]
  (cond
    (< bytes 1024) (str bytes " B/s")
    (< bytes (* 1024 1024)) (format "%.1f KB/s" (/ bytes 1024.0))
    :else (format "%.1f MB/s" (/ bytes (* 1024.0 1024)))))

(defn- print-download-progress [download active-peers]
  (let [ps (:piece-state download)
        verified (pieces/verified-count ps)
        total (:total-pieces ps)
        pct (if (zero? total) 0.0 (* 100.0 (/ verified total)))
        stats (:stats download)]
    (printf "\r  [%.1f%%] %d/%d pieces | %d peers | %s    "
            pct verified total (count active-peers)
            (format-bytes-rate (or (:rate stats) 0)))
    (flush)))

(defn- fail-no-peers
  "Fail the download: no usable peers remain."
  [download conn-stats total-attempted last-detail]
  (let [{:keys [connected failed]} @conn-stats]
    (println)
    (println (str "  No peers available. "
                  connected "/" total-attempted " connected, "
                  failed " failed. Last: " last-detail))
    (assoc download :state :failed
           :error {:reason :no-peers
                   :message (str "No peers available (" connected "/" total-attempted " connected)")})))

(defn watch-workers!
  "Close events-ch once every worker channel has closed.
   run-peer returns its thread channel, which closes when the worker
   exits — so all-closed means every worker is done. With zero workers
   the channel closes immediately instead of parking the coordinator."
  [worker-chs events-ch]
  (async/thread
    (doseq [worker-ch worker-chs]
      (async/<!! worker-ch))
    (async/close! events-ch)))

(s/fdef watch-workers!
  :args (s/cat :worker-chs coll? :events-ch any?)
  :ret any?)

(defn run-coordinator
  "Drive one download from events-ch to completion or swarm exhaustion.
   Handlers plan state transitions, the edge performs effects.
   Returns the final Download record.

   state — initial coordinator state map (see initial-coordinator-state):
           {:download ... :active-peers ... :blocks-received ... :expected-blocks ...
            :pending-dials #{...}}
   events-ch — channel of :peer-connected / :peer-message / :peer-disconnected maps
   env — {:message-ctx {:piece-hashes ... :piece-length ... :total-length ... :total-pieces ...}
          :output-layout (:ok (torrent/compile-output-layout info)) — the compiled
                         layout value (not the result envelope), derived once per download
          :ports {:network-port ... :disk-port ... :time-port ...}
          :conn-stats (atom {:connected n :failed n})
          :total-attempted n}"
  [state events-ch env]
  (loop [state state
         last-progress-time 0]

    (let [download (:download state)
          {:keys [message-ctx ports conn-stats total-attempted]} env
          {:keys [network-port time-port]} ports]
      (if (pieces/complete? (:piece-state download))
        (do
          (println)
          (println "  Download complete!")
          (doseq [[_ peer-info] (:active-peers state)]
            (network/close-peer network-port (:peer-data peer-info)))
          (assoc download :state :completed))

        (let [event (async/<!! events-ch)]
          (if (nil? event)
            ;; Channel closed, all peers gone
            (do
              (println)
              (println "  All peers disconnected.")
              (assoc download :state :failed
                     :error {:reason :no-peers :message "All peers disconnected"}))

            (let [now (time/now time-port)
                  show-progress? (> (- now last-progress-time) 2000)]

              (case (:type event)

                :peer-connected
                (let [[planned effects] (on-connected state event)
                      [performed _] (perform-effects! planned effects env)
                      download (:download performed)
                      active-peers (:active-peers performed)]
                  (swap! conn-stats update :connected inc)
                  (when show-progress?
                    (print-download-progress download active-peers))
                  (recur performed (if show-progress? now last-progress-time)))

                :peer-message
                (let [[planned effects] (on-message state event message-ctx)
                      [performed outcome] (perform-effects! planned effects env)]
                  (if (= :ok outcome)
                    (if (swarm-exhausted? performed)
                      ;; A send failure dropped the last peer: fail like a
                      ;; disconnect instead of waiting on a silent channel.
                      ;; (Message handling otherwise never removes peers,
                      ;; so this check is inert for all other paths.)
                      (fail-no-peers (:download performed) conn-stats total-attempted "send failure")
                      (let [wrote? (boolean (some :write-verified effects))]
                        (when (and show-progress? wrote?)
                          (print-download-progress (:download performed) (:active-peers performed)))
                        (recur performed (if (and show-progress? wrote?) now last-progress-time))))
                    (let [download (:download performed)]
                      (println)
                      (println (str "  Fatal effect error: " (get-in outcome [:fatal :message])))
                      (assoc download :state :failed :error (:fatal outcome)))))

                :peer-disconnected
                (let [{:keys [address reason]} event
                      [planned effects] (on-disconnected state event)
                      [performed _] (perform-effects! planned effects env)
                      download (:download performed)
                      active-peers (:active-peers performed)]
                  ;; Count each address once: a send-failure drop already
                  ;; counted its address, so its late duplicate event skips.
                  (when-not (contains? (:failed-addresses @conn-stats) address)
                    (swap! conn-stats (fn [stats]
                                        (-> stats
                                            (update :failed inc)
                                            (update :failed-addresses (fnil conj #{}) address)))))
                  (when show-progress?
                    (print-download-progress download active-peers))
                  (if (swarm-exhausted? performed)
                    (fail-no-peers download conn-stats total-attempted reason)
                    (recur performed
                           (if show-progress? now last-progress-time))))

                ;; Unknown event type
                (recur state last-progress-time)))))))))

(s/fdef run-coordinator
  :args (s/cat :state map? :events-ch any? :env map?)
  :ret map?)

(defn- fail-disk
  "Mark the download :failed with a :disk-error, naming the stage that
   refused. The stage leads the message so a truncated message still says
   whether the layout, its initialization or a piece write gave up.
   Returns the updated record."
  [download stage message]
  (assoc download
         :state :failed
         :error {:reason :disk-error
                 :message (str stage ": " message)}))

(defn- materialize-verified-pieces
  "Write every piece the record claims is verified out of the piece cache
   and into the output layout.

   A resumed record's verified bytes live in the piece cache, not in the
   output files, and initialize-output-layout only creates those files at
   their declared length. Copying them here is what makes :completed mean
   the content is on disk rather than that the files exist.

   A verified piece the cache no longer holds cannot be written, so it
   returns to :needed and the swarm fetches it again. Leaving it verified
   would let complete? answer true and report a finished download with a
   hole in it.

   Returns {:ok download} with the piece state updated, or the refusing
   port envelope {:error reason :message msg} unchanged. The envelope
   carries the in-progress :download, so a write that fails after earlier
   pieces were requeued does not discard that progress: the failed record
   keeps it instead of re-claiming the holey pieces as verified."
  [download disk-port output-layout]
  (loop [download download
         remaining (sort (:verified (:piece-state download)))]
    (if (empty? remaining)
      {:ok download}
      (let [piece-index (first remaining)
            rest-pieces (rest remaining)
            cached (disk/read-piece disk-port piece-index)]
        (cond
          (:error cached)
          (assoc cached :download download)

          (nil? (:ok cached))
          (let [requeued (pieces/requeue-verified (:piece-state download)
                                                  piece-index)]
            (if (:ok requeued)
              (recur (assoc download :piece-state (:ok requeued)) rest-pieces)
              ;; The index came out of the verified set this loop is walking,
              ;; so the transition cannot legitimately fail. Surface it
              ;; anyway: assoc'ing a nil piece state would strand the record.
              (assoc requeued :download download)))

          :else
          (let [written (disk/write-output-piece disk-port
                                                 output-layout
                                                 (:output-dir download)
                                                 piece-index
                                                 (:ok cached))]
            (if (:error written)
              (assoc written :download download)
              (recur download rest-pieces))))))))

(defn run-download
  "Run the download to completion. Blocking call.
   Connects to peers, requests pieces, writes verified pieces.
   The output layout is compiled once up front and handed to the port
   and the coordinator; a layout that cannot be compiled fails the
   download before any peer is dialed or file created.
   Returns the final Download record."
  [manager download]
  (let [{:keys [disk-port config]} manager
        compiled (torrent/compile-output-layout (:info (:torrent download)))]
    (if (:error compiled)
      (fail-disk download "Failed to compile output layout" (:message compiled))
      (let [layout (:ok compiled)
            init-result (disk/initialize-output-layout
                         disk-port
                         layout
                         (:output-dir download))
            ;; Materialize before asking whether the download is complete: a
            ;; resumed record's verified bytes are in the piece cache, and
            ;; initialize-output-layout only created the files at their
            ;; declared length. Asking first is what let :completed mean "the
            ;; files exist" instead of "the content is on disk". Skipped when
            ;; the layout was never created, so nothing is written into a
            ;; layout that failed to initialize.
            materialized (if (:error init-result)
                           init-result
                           (materialize-verified-pieces download
                                                        disk-port
                                                        layout))
            ;; Read only where materialization succeeded; the two failure
            ;; branches below still report on the original record.
            revived (:ok materialized)
            ;; Dial candidates capped exactly as the spawn below reads them,
            ;; so the empty guard and the worker spawn cannot drift apart.
            ;; From the original record: materialization only edits
            ;; :piece-state, never :peers, so this is identical on the
            ;; success path and well-defined on the failure paths, where
            ;; revived is nil and the cond returns before using it.
            peer-addresses (capped-peer-addresses (map :address (:peers download))
                                                  config)]
        (cond
          (:error init-result)
          (fail-disk download
                     "Failed to initialize output layout"
                     (:message init-result))

          (:error materialized)
          (fail-disk (:download materialized)
                     "Failed to materialize verified pieces"
                     (:message materialized))

          (pieces/complete? (:piece-state revived))
          (assoc revived :state :completed)

    ;; No dial candidates: no workers would spawn and the coordinator
    ;; would block on the event channel forever.
          (empty? peer-addresses)
          (assoc revived :state :failed
                 :error {:reason :no-peers
                         :message "No peers available: nothing to connect to"})

          :else
          (let [{:keys [network-port disk-port time-port]} manager
                torrent (:torrent revived)
                info (:info torrent)
                info-hash (:info-hash torrent)
                total-pieces (count (:pieces info))
                piece-hashes (:pieces info)
                piece-length (:piece-length info)
                ;; The compiled layout already carries the content length:
                ;; one derivation, no second walk of the declared files.
                total-length (:total layout)
                peer-id (let [b (byte-array 20)]
                          (.nextBytes (SecureRandom.) b)
                          b)
                events-ch (async/chan 256)
                total-attempted (count peer-addresses)
                conn-stats (atom {:connected 0 :failed 0})]

            (println (str "  Connecting to " total-attempted " peers..."))
            (println (str "  First 5 peer addresses: " (vec (take 5 peer-addresses))))

      ;; Spawn peer workers; their channels close on worker exit,
      ;; so watching them tells the coordinator when all peers are gone.
            (let [worker-chs (mapv (fn [addr]
                                     (peer-worker/run-peer network-port info-hash peer-id
                                                           addr total-pieces events-ch))
                                   peer-addresses)]
              (watch-workers! worker-chs events-ch))

      ;; Hand the event channel to the coordinator loop
            (run-coordinator (initial-coordinator-state revived peer-addresses)
                             events-ch
                             {:message-ctx {:piece-hashes piece-hashes
                                            :piece-length piece-length
                                            :total-length total-length
                                            :total-pieces total-pieces}
                              :output-layout layout
                              :ports {:network-port network-port
                                      :disk-port disk-port
                                      :time-port time-port}
                              :conn-stats conn-stats
                              :total-attempted total-attempted})))))))

(s/fdef run-download
  :args (s/cat :manager ::download-manager :download map?)
  :ret map?)
