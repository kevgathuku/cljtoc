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
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.time :as time])
  (:import [java.util UUID]))

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

(def valid-states #{:idle :starting :downloading :paused :completed :failed})

(defn- download-error
  [reason message & [failed-piece]]
  {:error reason :message message :failed-piece failed-piece})

(defn- parse-torrent [disk-port torrent-path]
  (let [ch (async/chan 1)]
    (async/go
      (let [result-chan (disk/read-torrent-file disk-port torrent-path)
            result (async/<! result-chan)]
        (if (:error result)
          (async/>! ch (download-error :invalid-torrent (:message result)))
          (async/>! ch {:ok (:ok result)}))))
    ch))

(defn- announce-to-tracker [network-port torrent]
  (let [ch (async/chan 1)]
    (async/go
      (let [result (async/<! (network/announce network-port torrent))]
        (if (:error result)
          (async/>! ch (download-error :tracker-error (:message result)))
          (async/>! ch {:ok (:ok result)}))))
    ch))

(defn- select-next-piece [piece-state peers]
  (let [peer-availables (map :bitfield peers)]
    (pieces/select-piece piece-state (first peer-availables) peer-availables)))

(defn- request-pieces [manager download peer]
  (let [piece-state (:piece-state download)
        result (select-next-piece piece-state (:peers download))]
    (if-let [piece-idx (:ok result)]
      (let [marked (pieces/mark-in-flight piece-state piece-idx)]
        (if (:error marked)
          {:ok nil}
          {:ok {:piece-index piece-idx
                :piece-state (:ok marked)}}))
      {:ok nil})))

(defn- verify-and-write-piece [disk-port piece-index bytes piece-hash]
  (let [verify-result (pieces/verify-piece piece-index bytes piece-hash)]
    (if (:error verify-result)
      {:error (:error verify-result)}
      (let [write-result (disk/write-piece disk-port piece-index bytes)]
        (if (:error write-result)
          {:error (:error write-result)}
          {:ok piece-index})))))

(defn initial-stats []
  (let [now (System/currentTimeMillis)]
    (->DownloadStats now nil 0 0 now)))

(defn update-stats-bytes [stats bytes-received]
  (let [now (System/currentTimeMillis)
        prev-bytes (:bytes-downloaded stats)
        prev-time (:last-update stats)
        elapsed-seconds (max 1 (/ (- now prev-time) 1000.0))
        new-total (+ prev-bytes bytes-received)
        rate (long (/ bytes-received elapsed-seconds))]
    (-> stats
        (assoc :bytes-downloaded new-total)
        (assoc :last-update now)
        (assoc :rate rate))))

(defn calculate-rate [stats]
  (let [now (System/currentTimeMillis)
        elapsed-seconds (/ (- now (:last-update stats)) 1000.0)
        bytes-downloaded (:bytes-downloaded stats)]
    (if (and (> elapsed-seconds 0) (> bytes-downloaded 0))
      (long (/ bytes-downloaded elapsed-seconds))
      0)))

(defn initial-download [torrent output-dir]
  (let [info (:info torrent)
        total-pieces (count (:pieces info))
        piece-state (pieces/initial-piece-state total-pieces)]
    (->Download (UUID/randomUUID)
                torrent
                piece-state
                #{}
                :starting
                output-dir
                (initial-stats)
                nil)))

(defn start-download [manager torrent-path output-dir]
  (let [{:keys [network-port disk-port]} manager
        parse-result (async/<!! (parse-torrent disk-port torrent-path))]
    (if (:error parse-result)
      parse-result
      (let [torrent (:ok parse-result)
            download (initial-download torrent output-dir)
            announce-result (async/<!! (announce-to-tracker network-port torrent))]
        (if (:error announce-result)
          (assoc download :state :failed :error (:error announce-result))
          (let [peers (:ok announce-result)]
            (assoc download
                   :state :downloading
                   :peers (set (map (fn [addr]
                                       (->Peer addr addr 6881 #{} true false true false 0 0))
                                     peers)))))))))

(defn progress [download]
  (let [piece-state (:piece-state download)
        total (pieces/verified-count piece-state)
        total-pieces (:total-pieces piece-state)
        percent (if (zero? total-pieces)
                  0.0
                  (* 100.0 (/ total total-pieces)))
        stats (:stats download)
        bytes-downloaded (:bytes-downloaded stats)
        rate (calculate-rate stats)]
    {:percent percent
     :pieces-complete total
     :pieces-total total-pieces
     :bytes-downloaded bytes-downloaded
     :rate-bytes-per-sec rate
     :peers-connected (count (:peers download))
     :state (:state download)}))

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
         (let [save-result (async/<!! (disk/save-state disk-port paused-download))]
           (if (:error save-result)
             {:error (:error save-result) :message "Failed to persist state"}
             {:ok paused-download}))
         {:ok paused-download}))
     {:error :not-running :message "Download is not running"})))

(defn resume-download
  "Resume a paused download.
   - Loads persisted state from disk
   - Reconnects to peers
   - Returns download in :downloading state"
  ([download]
   (resume-download nil nil download))
  ([disk-port network-port download]
   (if (= :paused (:state download))
     (let [download-id (:id download)
           loaded-download (if disk-port
                           (async/<!! (disk/load-state disk-port download-id))
                           download)
           restored (if (or (nil? loaded-download) (:error loaded-download))
                    download
                    loaded-download)
           resumed-download (assoc restored :state :downloading)]
       {:ok resumed-download})
     {:error :not-paused :message "Download is not paused"})))

(defn load-persisted-state [disk-port download-id]
  "Load persisted download state from disk."
  (if disk-port
    (async/<!! (disk/load-state disk-port download-id))
    nil))

(defn persist-download-state [disk-port download]
  "Persist current download state to disk for recovery."
  (if disk-port
    (async/<!! (disk/save-state disk-port download))
    {:ok :no-disk-port}))

(defn stop-download [download]
  (assoc download :state :idle :peers #{}))

;; ============================================================================
;; Error Handling (User Story 3)
;; ============================================================================

(defn requeue-piece [download piece-index]
  "Move a piece back to needed state for re-download.
   Returns updated download."
  (let [piece-state (:piece-state download)
        result (pieces/requeue-piece piece-state piece-index)]
    (if (:error result)
      download
      (assoc download :piece-state (:ok result)))))

(defn handle-piece-verification-failure [download piece-index]
  "Handle piece verification failure by re-queuing the piece.
   Returns updated download with piece back in needed state."
  (requeue-piece download piece-index))

(defn handle-peer-disconnect [download peer-id]
  "Handle peer disconnection by removing peer and re-queueing in-flight pieces.
   Returns updated download."
  (let [peer (first (filter #(= (:id %) peer-id) (:peers download)))
        in-flight-pieces (if peer (:in-flight (:piece-state download)) #{})
        download (update download :peers disj peer)]
    (reduce requeue-piece download in-flight-pieces)))

(defn add-peer [download peer]
  "Add a new peer to the download.
   Returns updated download."
  (update download :peers conj peer))

(defn remove-peer [download peer-id]
  "Remove a peer from the download by ID.
   Returns updated download."
  (let [peer (first (filter #(= (:id %) peer-id) (:peers download)))]
    (if peer
      (update download :peers disj peer)
      download)))

(defn transition-to-failed [download error-info]
  "Transition download to failed state with error information.
   Returns updated download."
  (assoc download :state :failed :error error-info))

(defn can-retry? [download]
  "Check if download can be retried (hasn't exceeded retry limit)."
  (let [retry-count (or (get-in download [:error :retry-count]) 0)]
    (< retry-count 3)))

(defn retry-download [download]
  "Retry a failed download by resetting state and clearing error."
  (if (can-retry? download)
    (let [current-retry (or (get-in download [:error :retry-count]) 0)
          new-retry-count (inc current-retry)
          download (assoc download :state :starting :error {:retry-count new-retry-count})]
      download)
    {:error :max-retries-exceeded :message "Download has exceeded maximum retry attempts"}))

(defn get-failed-piece [download]
  "Get the piece index that failed, if any."
  (get-in download [:error :failed-piece]))

(defn has-active-peers? [download]
  "Check if download has any active peer connections."
  (pos? (count (:peers download))))

(defn handle-no-peers [download]
  "Handle the case when all peers disconnect.
   Returns updated download with appropriate state."
  (if (pieces/complete? (:piece-state download))
    (assoc download :state :completed)
    (transition-to-failed download
      {:reason :no-peers
       :message "No peers available for download"
       :failed-piece nil})))

;; ============================================================================
;; Spec Validation
;; ============================================================================

(s/def ::download-id uuid?)
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

(s/fdef initial-stats
  :ret (s/keys :req-un [::started-at]))

(s/fdef initial-download
  :args (s/cat :torrent map? :output-dir string?)
  :ret (s/keys :req-un [::download-id ::state]))

(s/fdef progress
  :args (s/cat :download map?)
  :ret ::progress-response)

(s/fdef pause-download
  :args (s/cat :disk-port (s/? any?) :download map?)
  :ret (s/or :ok (s/keys :req-un [::state])
              :error map?))

(s/fdef resume-download
  :args (s/cat :disk-port (s/? any?) :network-port (s/? any?) :download map?)
  :ret (s/or :ok (s/keys :req-un [::state])
              :error map?))

(s/fdef stop-download
  :args (s/cat :download map?)
  :ret (s/keys :req-un [::state ::peers]))

