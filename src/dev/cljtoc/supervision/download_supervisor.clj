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

   The re-announcer and watchdog children land with their own slices
   (torrent-client-clj-0c9.10 and 0c9.11); they join the coordinator
   under the same rest-for-one policy."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.ports.time :as time]
            [dev.cljtoc.supervision.restart :as restart]))

(def default-budget
  "How often the supervisor may restart the subtree inside the window.
   These are the values ADR-0011 left to the implementation: a subtree
   that crashes three times inside a minute is failing for good, not
   flapping."
  {:limit 3
   :window-ms 60000})

(defn- now-ms
  "The current time in milliseconds, or 0 when the manager carries no
   clock: without one the restart window never slides, and the budget
   reads as a plain counter."
  [manager]
  (if-let [time-port (:time-port manager)]
    (long (time/now time-port))
    0))

(defn- budget-exhausted-record
  "The failed record a download gets when its subtree crashed more often
   than the budget allows. The reason says the supervisor gave up, not
   that the download failed on its own terms."
  [download-record restarts window-ms]
  (assoc download-record
         :state :failed
         :error {:reason :restart-budget-exhausted
                 :message (str "The download crashed " restarts
                               " times within " window-ms
                               "ms and was not restarted again")}))

(defn supervise-download
  "Run one download to a terminal state under the download supervisor.

   `prepare` builds the run plan and `run` runs the subtree; both
   default to the real ones, so a caller runs the real download and a
   test can drive the restart path through them. `opts` is what
   prepare-run takes (nil for a plain download).

   The subtree publishes its record as it progresses, and a restart
   resumes from the published record: the pieces already verified are
   not fetched again. The plan itself is prepared once, so a restart
   does not resolve the output layout against the filesystem again.

   Returns the download's terminal record: the finished download, or a
   failed one carrying :restart-budget-exhausted when the budget ran
   out."
  [manager download-record {:keys [opts prepare run budget]
                            :or {opts nil
                                 prepare download/prepare-run
                                 run download/run-subtree
                                 budget default-budget}}]
  (let [outcome (prepare manager download-record opts)]
    (if (= :finished (:status outcome))
      (:record outcome)
      (let [plan (:plan outcome)
            published (atom (:download plan))
            publish (fn [record] (reset! published record))
            child (fn [_attempt]
                    (run manager (assoc plan :download @published) publish))
            result (restart/supervise child
                                      {:limit (:limit budget)
                                       :window-ms (:window-ms budget)
                                       :now (fn [] (now-ms manager))})]
        (if (contains? result :ok)
          (:ok result)
          (budget-exhausted-record download-record
                                   (:restarts result)
                                   (:window-ms budget)))))))

;; Excluded from stest/check: the manager carries the live ports the run
;; drives, and no generator can conjure a network or disk port.
(s/fdef supervise-download
  :args (s/cat :manager map? :download-record map? :options (s/? map?))
  :ret map?)
