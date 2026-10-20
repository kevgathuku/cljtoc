(ns dev.cljtoc.supervision.download-supervisor
  "Supervises one download's subtree.

   The coordinator is the download supervisor's child: a crash in it
   restarts the subtree while the restart budget holds, and when the
   budget runs out the download fails with :restart-budget-exhausted
   instead of being restarted forever.

   The peer workers are the download supervisor's children too: the peer
   supervisor owns them, the event channel they write to, and the
   per-connection restart budget. A peer worker that crashes is
   restarted on its own, so a flaky peer never restarts the coordinator.

   The subtree belongs to this download alone. That is what keeps one
   flaky torrent from restarting its siblings: the session supervisor
   above owns one download supervisor per torrent, and a restart here
   never reaches outside this download (ADR-0012).

   The subtree is the unit the re-announcer and watchdog children join
   (torrent-client-clj-0c9.10 and 0c9.11): they land inside it, and a
   failure in one restarts this download's subtree and nothing else."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [dev.cljtoc.coordination.peer-worker :as peer-worker]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.supervision.peer-supervisor :as peer-supervisor]
            [dev.cljtoc.supervision.restart :as restart]
            [dev.cljtoc.supervision.watchdog :as watchdog]))

(defn peer-supervisor-for
  "The peer supervisor a download's subtree runs on.

   It owns the peer workers and the event channel they write to, and it
   restarts a worker that crashes while the per-connection budget
   holds. The plan carries the peers to dial and the torrent the
   handshake verifies against."
  [manager plan]
  (let [{:keys [network-port]} manager
        torrent (:torrent (:download plan))
        info-hash (:info-hash torrent)
        total-pieces (count (:pieces (:info torrent)))
        peer-id (network/peer-id network-port)]
    (peer-supervisor/start
     {:spawn (fn [events-ch address]
               (peer-worker/run-peer network-port info-hash peer-id
                                     address total-pieces events-ch))
      :addresses (:peer-addresses plan)
      :budget (restart/budget-for manager peer-supervisor/default-connection-budget)})))

(s/fdef peer-supervisor-for
  :args (s/cat :manager map? :plan map?)
  :ret map?)

(defn budget-exhausted-record
  "The failed record a download gets when a supervisor gave up on it: the
   subtree crashed more often than the budget allows. The reason says the
   supervisor gave up, not that the download failed on its own terms. The
   record it starts from is the one the crashed subtree published, so the
   pieces it verified are not lost."
  [download-record restarts window-ms]
  (download/transition-to-failed
   download-record
   {:reason :restart-budget-exhausted
    :message (str "The download's supervisor gave up after " restarts
                  " restarts within " window-ms "ms")}))

(s/fdef budget-exhausted-record
  :args (s/cat :download-record map? :restarts nat-int? :window-ms pos-int?)
  :ret map?)

(defn run-download
  "Run one download to a terminal state, without the restart policy.

   The peer supervisor owns the workers and the coordinator drives them
   over the channel it owns. A crash travels to the caller instead of
   being retried here: the download supervisor's supervise-download is
   the entry point that restarts a crashed subtree.

   `opts` is what prepare-run takes (nil for a plain download)."
  ([manager download-record]
   (run-download manager download-record nil))
  ([manager download-record opts]
   (let [outcome (download/prepare-run manager download-record opts)]
     (if (= :ready (:status outcome))
       (let [plan (:plan outcome)
             peer-supervisor (peer-supervisor-for manager plan)]
         (try
           (download/run-subtree manager plan nil peer-supervisor)
           (finally
             ((:stop peer-supervisor)))))
       (:record outcome)))))

(s/fdef run-download
  :args (s/cat :manager map? :download-record map? :opts (s/? map?))
  :ret map?)

(defn supervise-download
  "Run one download to a terminal state under the download supervisor.

   `prepare` builds the run plan and `run` runs the subtree; both
   default to the real ones, so a caller runs the real download and a
   test can drive the restart path through them. `opts` is what
   prepare-run takes (nil for a plain download).

   The subtree publishes its record as it progresses, and a restart
   resumes from the published record: the pieces already verified are
   not fetched again, and the pieces the crashed attempt left in flight
   are requeued, so the restarted swarm asks for them again. The plan
   itself is prepared once, so a restart does not resolve the output
   layout against the filesystem again.

   `on-progress` receives that record as it moves, so a supervisor
   above this one keeps the download's progress even when this
   supervisor itself crashes.

   Returns the download's terminal record: the finished download, or a
   failed one carrying :restart-budget-exhausted when the budget ran
   out."
  [manager download-record {:keys [opts prepare run budget on-progress watchdog]
                            :or {opts nil
                                 prepare download/prepare-run
                                 run download/run-subtree
                                 budget restart/default-budget}}]
  (let [outcome (prepare manager download-record opts)]
    (if (= :finished (:status outcome))
      (:record outcome)
      (let [plan (:plan outcome)
            published (atom (:download plan))
            ;; The coordinator's state, for the watchdog: it watches the
            ;; blocks the download receives, not the record it publishes.
            observed (atom nil)
            publish (fn [state]
                      (reset! published (:download state))
                      (reset! observed state)
                      (when on-progress (on-progress (:download state))))
            watchdog-opts (merge {:deadline-ms watchdog/default-stall-deadline-ms
                                  :poll-ms watchdog/default-poll-ms}
                                 watchdog)
            child (fn []
                    (let [peer-supervisor (peer-supervisor-for manager plan)
                          watchdog (watchdog/start
                                    {:state observed
                                     :now (fn [] (restart/now-ms (:time-port manager)))
                                     :deadline-ms (:deadline-ms watchdog-opts)
                                     :poll-ms (:poll-ms watchdog-opts)
                                     :fire (fn []
                                             (async/>!! (:events-ch peer-supervisor)
                                                        {:type :watchdog-fired}))})]
                      (try
                        (run manager
                             (assoc plan :download
                                    (download/requeue-stranded @published))
                             publish
                             peer-supervisor)
                        (finally
                          (watchdog)
                          ((:stop peer-supervisor))))))
            result (restart/supervise child (restart/budget-for manager budget))]
        (if (contains? result :ok)
          (:ok result)
          (budget-exhausted-record @published
                                   (:restarts result)
                                   (:window-ms budget)))))))

;; Excluded from stest/check: the manager carries the live ports the run
;; drives, and no generator can conjure a network or disk port.
(s/fdef supervise-download
  :args (s/cat :manager map? :download-record map? :options map?)
  :ret map?)
