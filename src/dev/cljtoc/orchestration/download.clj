(ns dev.cljtoc.orchestration.download
  "Download orchestration - coordinates torrent downloads.

   This namespace manages the end-to-end download process:
   - Parsing .torrent files
   - Connecting to trackers to get peers
   - Managing peer connections
   - Selecting pieces using rarest-first
   - Downloading and verifying pieces
   - Writing verified pieces to disk

   Pure [state effects] planning lives in
   dev.cljtoc.orchestration.coordinator; this namespace keeps lifecycle,
   the effect edge, and run wiring.

   All I/O is performed through injected port protocols, making this
   code testable with mock implementations."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.domain.peer-address :as peer-address]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.orchestration.coordinator :as coordinator]
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

(defn- announce-progress
  "The progress a tracker (re-)announce must report for download:
   :downloaded is the verified bytes already on disk, :left the rest.
   Announcing zero on resume tells the tracker nothing was fetched and
   skews its leecher accounting. The tail piece counts at its real
   length; missing size metadata announces zero rather than throwing."
  [download]
  (let [info (get-in download [:torrent :info] {})
        total (torrent/total-size info)
        piece-length (or (:piece-length info) 0)
        downloaded (reduce + 0
                           (map (fn [piece-index]
                                  (pieces/piece-length piece-index piece-length total))
                                (:verified (:piece-state download))))]
    {:downloaded downloaded
     :left (max 0 (- total downloaded))}))

(defn- announce-to-tracker [network-port download]
  (let [result (network/announce network-port
                                 (:torrent download)
                                 (announce-progress download))]
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

(defn- rate-at
  "Average transfer rate at one clock reading: total bytes downloaded
   divided by active seconds since :started-at. Paused, failed, and crashed
   gaps accumulated in :downtime-ms across resumes do not count: the average
   measures transferring time, not wall time. Records predating started-at
   tracking report 0."
  [now stats]
  (let [started (:started-at stats)
        downtime (or (:downtime-ms stats) 0)
        elapsed-seconds (if started
                          (/ (max 0 (- (- now started) downtime)) 1000.0)
                          0)
        bytes-downloaded (:bytes-downloaded stats)]
    (if (and (> elapsed-seconds 0) (> bytes-downloaded 0))
      (long (/ bytes-downloaded elapsed-seconds))
      0)))

(defn- mark-suspended
  "Stamp when this run stopped into the stats. A nil clock (callers
   without a time port) leaves the record untouched: resume falls back
   to the last verified byte exactly as before."
  [download now]
  (if (some? now)
    (assoc-in download [:stats :suspended-at] now)
    download))

(defn accumulate-downtime
  "Fold the dead gap into the stats.
   The gap runs from the recorded suspension (pause/failure time) when
   the record carries one, else from the last verified byte -- an
   unobserved crash leaves no suspension stamp, so the last byte is the
   only boundary. Live-but-idle time before a recorded suspension stays
   in the rate denominator: the run was up, just not moving bytes. The
   stamp is single-use and last-update refreshes, so the next gap starts
   here. Records predating downtime tracking resume unchanged. Returns
   the updated download."
  [time-port download]
  (let [now (time/now time-port)
        stats (:stats download)
        signals (filter some? [(:last-update stats) (:suspended-at stats)])
        gap (if (seq signals) (max 0 (- now (apply max signals))) 0)]
    (assoc download :stats (-> stats
                               (assoc :downtime-ms (+ (or (:downtime-ms stats) 0)
                                                      gap))
                               (assoc :last-update (let [last (:last-update stats)]
                                                     (if (and last (> last now))
                                                       last
                                                       now)))
                               (dissoc :suspended-at)))))

(s/fdef accumulate-downtime
  :args (s/cat :time-port any? :download map?)
  :ret map?)

(defn calculate-rate
  "Average transfer rate over the run: total bytes downloaded divided by
   active seconds since :started-at. Averaging over the run (instead of the
   gap since :last-update) keeps the reported speed stable: right after the
   final block the last-gap quotient explodes into fantasy GB/s. Paused,
   failed, and crashed gaps accumulated in :downtime-ms do not count. Once
   :completed-at is stamped the rate freezes there, so late status calls
   agree with the persisted record instead of decaying toward zero.
   Records predating started-at tracking report 0."
  [time-port stats]
  (if-let [completed (:completed-at stats)]
    (rate-at completed stats)
    (rate-at (time/now time-port) stats)))

(s/fdef calculate-rate
  :args (s/cat :time-port any? :stats map?)
  :ret nat-int?)

(defn complete-download
  "Mark the download completed, finalizing its stats for persistence.
   Stamps completed-at, refreshes last-update, and pins the rate at the
   run average instead of the last block's instantaneous value, so the
   saved record describes the finished run rather than its final block.
   Returns the updated download."
  [time-port download]
  (let [now (time/now time-port)
        stats (:stats download)]
    (assoc download
           :state :completed
           :stats (assoc stats
                         :completed-at now
                         :last-update now
                         :rate (rate-at now stats)))))

(s/fdef complete-download
  :args (s/cat :time-port any? :download map?)
  :ret (s/keys :req-un [::state]))

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
            announce-result (announce-to-tracker network-port download)]
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
   - Stamps when the run stopped, so resume excludes only the dead gap
   - Persists state to disk via IDiskPort
   - Returns updated download with :paused state"
  ([download]
   (pause-download nil nil download))
  ([disk-port download]
   (pause-download nil disk-port download))
  ([time-port disk-port download]
   (if (= :downloading (:state download))
     (let [paused-download (mark-suspended (assoc download :state :paused :peers #{})
                                           (when time-port (time/now time-port)))]
       (if disk-port
         (let [save-result (disk/save-state disk-port paused-download)]
           (if (:error save-result)
             {:error (:error save-result) :message "Failed to persist state"}
             {:ok paused-download}))
         {:ok paused-download}))
     {:error :not-running :message "Download is not running"})))

(s/fdef pause-download
  :args (s/alt :bare (s/cat :download map?)
               :with-disk (s/cat :disk-port any? :download map?)
               :with-time (s/cat :time-port any? :disk-port any? :download map?))
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

(declare materialize-verified-pieces)

(defn- reconcile-verified-pieces
  "Reconcile the record's verified set against the piece cache before any
   tracker announce.

    The announce below would otherwise report stale verified bytes and fail
    a download the cache already completes. Returns {:ok download} carrying
    the reconciled record, plus :layout holding the compiled output layout
    (nil when there is nothing to reconcile against: no disk port, an
    uncompilable layout, or a failed materialization). Those fall through
    to the announce path, where run-download reports the layout failure
    exactly as before."
  [disk-port download]
  (if (nil? disk-port)
    {:ok download :layout nil}
    (let [compiled (torrent/compile-output-layout (:info (:torrent download)))]
      (if (:error compiled)
        {:ok download :layout nil}
        (let [materialized (materialize-verified-pieces
                            download disk-port (:ok compiled))]
          (if (:error materialized)
            {:ok download :layout nil}
            (assoc materialized :layout (:ok compiled))))))))

(defn resume-download
  "Resume a paused or failed download.

   - Loads persisted state from disk when a disk port is given
   - Reconciles verified pieces against the piece cache before
     announcing: a record the cache already completes comes back
     :completed without contacting the tracker, and the announce
     otherwise reports the corrected progress
   - Re-announces to the tracker when a network port is given: the saved
     peer set is the one that just failed (pause clears it outright), and
     run-download derives every worker from :peers
   - Requeues pieces the dead run left in flight
   - Returns the download in :downloading state, or :completed when the
     cache already holds every verified piece (with the output layout
     initialized: the skipped run never initializes it, and the piece
     writer alone would leave a declared zero-length file absent)"
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
          ;; Announce the reconciled record, not the revived one: pieces
          ;; the cache no longer holds went back to :needed above, so the
          ;; tracker hears the corrected progress. A reconciled-complete
          ;; record skips the announce and the swarm outright: there is
          ;; nothing left to fetch, and gating completion on a tracker
          ;; answer stranded fully-cached resumes on a dead tracker.
         (let [{reconciled :ok layout :layout}
               (reconcile-verified-pieces disk-port revived)
                ;; The swarm is skipped, so run-download never initializes
                ;; the output layout -- and the piece writer only opens
                ;; files piece bytes touch. Initialize here so a declared
                ;; zero-length file exists before the :completed report.
                ;; An init failure falls through to the announce path,
                ;; where run-download reports it exactly as before.
               initialized (when (and (pieces/complete? (:piece-state reconciled))
                                      (some? layout))
                             (disk/initialize-output-layout
                              disk-port layout (:output-dir reconciled)))]
           (if (and (some? initialized) (not (:error initialized)))
             {:ok (assoc reconciled :state :completed)}
             (let [announce-result (announce-to-tracker network-port reconciled)]
               (if (:error announce-result)
                 announce-result
                  ;; :layout travels at the envelope level (never inside
                  ;; the record): resume-and-run hands it to run-download
                  ;; so the reconciled materialization is not repeated.
                 {:ok (assoc reconciled
                             :peers (build-peers (:ok announce-result)))
                  :layout layout}))))
         {:ok revived}))
     {:error :not-paused
      :message (str "Download is not resumable from state "
                    (:state download))})))

(s/fdef resume-download
  :args (s/cat :disk-port (s/? any?) :network-port (s/? any?) :download map?)
  :ret (s/or :ok (s/keys :req-un [::state])
             :error map?))

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
;; Coordinator (pure planning kernel)
;; ============================================================================
;;
;; The pure [state effects] planning kernel now lives in
;; dev.cljtoc.orchestration.coordinator (zero port requires).
;; run-event-loop below calls into it; the loop edge (perform-effects!)
;; and run wiring stay here.

(defn- fail-verified-write
  "Unwind a failed verified-piece write: requeue the write's piece plus
   every follow-up assignment this batch planned (their sends never ran),
   close every connection (a failed download must not leak workers), and
   fail so the record stays retryable. Returns [state {:fatal ...}]."
  [state effects piece-idx message network-port]
  (let [send-addrs (distinct (keep #(get-in % [:send :address]) effects))
        state (reduce (fn [unwound address]
                        (if-let [assigned (get-in unwound [:active-peers address :assigned-piece])]
                          (coordinator/requeue-assignment unwound address assigned)
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
                               (coordinator/requeue-assignment address assigned)
                               (update :active-peers dissoc address)
                               (update :blocks-received dissoc address)
                               (update :expected-blocks dissoc address))
                           rest-effects))
                  (recur state rest-effects)))))

          (:write-verified effect)
          (let [{:keys [piece-idx data]} (:write-verified effect)
                download (:download state)
                result (disk/write-piece disk-port (:id download) piece-idx data)]
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

(defn run-event-loop
  "Drive one download from events-ch to completion or swarm exhaustion.
   Handlers plan state transitions, the edge performs effects.
   Returns the final Download record.

   state — initial coordinator state map (see coordinator/initial-state):
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
          (complete-download time-port download))

        (let [event (async/<!! events-ch)]
          (if (nil? event)
            ;; Channel closed, all peers gone
            (do
              (println)
              (println "  All peers disconnected.")
              (mark-suspended (assoc download :state :failed
                                     :error {:reason :no-peers :message "All peers disconnected"})
                              (time/now time-port)))

            (let [now (time/now time-port)
                  show-progress? (> (- now last-progress-time) 2000)]

              (case (:type event)

                :peer-connected
                (let [[planned effects] (coordinator/on-connected state event)
                      [performed _] (perform-effects! planned effects env)
                      download (:download performed)
                      active-peers (:active-peers performed)]
                  (swap! conn-stats update :connected inc)
                  (when show-progress?
                    (print-download-progress download active-peers))
                  (recur performed (if show-progress? now last-progress-time)))

                :peer-message
                (let [[planned effects] (coordinator/on-message state event message-ctx)
                      [performed outcome] (perform-effects! planned effects env)]
                  (if (= :ok outcome)
                    (if (coordinator/swarm-exhausted? performed)
                      ;; A send failure dropped the last peer: fail like a
                      ;; disconnect instead of waiting on a silent channel.
                      ;; (Message handling otherwise never removes peers,
                      ;; so this check is inert for all other paths.)
                      (mark-suspended (fail-no-peers (:download performed) conn-stats total-attempted "send failure")
                                      (time/now time-port))
                      (let [wrote? (boolean (some :write-verified effects))]
                        (when (and show-progress? wrote?)
                          (print-download-progress (:download performed) (:active-peers performed)))
                        (recur performed (if (and show-progress? wrote?) now last-progress-time))))
                    (let [download (:download performed)]
                      (println)
                      (println (str "  Fatal effect error: " (get-in outcome [:fatal :message])))
                      (mark-suspended (assoc download :state :failed :error (:fatal outcome))
                                      (time/now time-port)))))

                :peer-disconnected
                (let [{:keys [address reason]} event
                      [planned effects] (coordinator/on-disconnected state event)
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
                  (if (coordinator/swarm-exhausted? performed)
                    (mark-suspended (fail-no-peers download conn-stats total-attempted reason)
                                    (time/now time-port))
                    (recur performed
                           (if show-progress? now last-progress-time))))

                ;; Unknown event type
                (recur state last-progress-time)))))))))

(s/fdef run-event-loop
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
   returns to :needed and the swarm fetches it again, as does a piece
   whose cached bytes fail their torrent hash -- the port returns
   whatever the cache file holds, so a truncated file would otherwise
   land in the output while the piece stays verified. Leaving either
   verified would let complete? answer true and report a finished
   download with a hole in it.

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
            cached (disk/read-piece disk-port (:id download) piece-index)]
        (cond
          (:error cached)
          (assoc cached :download download)

          ;; Bytes the cache can no longer vouch for go back to the swarm:
          ;; missing bytes, or bytes failing their torrent hash (the real
          ;; port returns whatever the cache file holds, so a truncated
          ;; file would otherwise land in the output while the piece stays
          ;; verified). Without hashes there is nothing to check against.
          (let [cached-bytes (:ok cached)
                expected (nth (get-in download [:torrent :info :pieces])
                              piece-index nil)]
            (or (nil? cached-bytes)
                (and (some? expected)
                     (not (:ok (pieces/verify-piece
                                piece-index cached-bytes expected))))))
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
   opts is optional: {:materialized? true :layout layout} carries a
   materialization resume-download already completed into this run, so
   the cached pieces are not read, verified, and written a second time.
   The caller guarantees the layout was compiled from this download's
   own info and the materialization ran after its last piece-state
   change -- only resume-and-run sets this, immediately after its own
   reconcile, over the untouched record. Anything else (missing flag,
   missing or invalid layout) runs the full pass exactly as before.
   Returns the final Download record."
  ([manager download]
   (run-download manager download nil))
  ([manager download opts]
   (let [{:keys [disk-port time-port config]} manager
         carried (:layout opts)
         ;; The O(1) shape gate, not the O(files) invariant check: the
         ;; layout came out of compile-output-layout in this same process,
         ;; so this only refuses a caller that forged the opts map.
         use-carried? (and (:materialized? opts)
                           (some? carried)
                           (disk/valid-output-layout? carried))
         compiled (if use-carried?
                    {:ok carried}
                    (torrent/compile-output-layout (:info (:torrent download))))]
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
             ;; layout that failed to initialize -- and skipped wholesale
             ;; when the caller carried a completed materialization in.
             materialized (cond
                            (:error init-result)
                            init-result

                            use-carried?
                            {:ok download}

                            :else
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
           (complete-download time-port revived)

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
             (run-event-loop (coordinator/initial-state revived peer-addresses)
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
                              :total-attempted total-attempted}))))))))

(s/fdef run-download
  :args (s/cat :manager ::download-manager :download map? :opts (s/? map?))
  :ret map?)
