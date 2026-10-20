(ns dev.cljtoc.supervision.download-supervisor
  "Supervises one download's subtree.

   The coordinator is the download supervisor's child: a crash in it
   restarts the subtree while the restart budget holds, and when the
   budget runs out the download fails with :restart-budget-exhausted
   instead of being restarted forever.

   The subtree belongs to this download alone. That is what keeps one
   flaky torrent from restarting its siblings: the session supervisor
   above owns one download supervisor per torrent, and a restart here
   never reaches outside this download (ADR-0012).

   The subtree is the unit the re-announcer and watchdog children join
   (torrent-client-clj-0c9.10 and 0c9.11): they land inside it, and a
   failure in one restarts this download's subtree and nothing else."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.supervision.restart :as restart]))

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
  [manager download-record {:keys [opts prepare run budget on-progress]
                            :or {opts nil
                                 prepare download/prepare-run
                                 run download/run-subtree
                                 budget restart/default-budget}}]
  (let [outcome (prepare manager download-record opts)]
    (if (= :finished (:status outcome))
      (:record outcome)
      (let [plan (:plan outcome)
            published (atom (:download plan))
            publish (fn [record]
                      (reset! published record)
                      (when on-progress (on-progress record)))
            child (fn []
                    (run manager
                         (assoc plan :download
                                (download/requeue-stranded @published))
                         publish))
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
