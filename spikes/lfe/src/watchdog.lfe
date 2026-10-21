;;;; Watchdog for one download, ported from
;;;; dev.cljtoc.supervision.watchdog.
;;;;
;;;; The Clojure watchdog poller runs inside a download's supervised
;;;; subtree: when the download's progress marker stops moving for the
;;;; stall deadline it fires, which crashes the subtree so the download
;;;; supervisor restarts it. The port keeps the same decision, the same
;;;; marker, the same deadline, and the same injected clock and fire
;;;; callback; it is a gen_server so a supervisor owns it like any other
;;;; child.
;;;;
;;;; The Clojure poller returns nil after firing, so one stall ends the
;;;; watchdog. The port does the same: it stops normally after a fire, and
;;;; restarting the download is the supervisor's job, not the watchdog's.

(defmodule watchdog
  (behaviour gen_server)
  (export
   (child_spec 1)
   (deadline 0)
   (handle_info 2)
   (init 1)
   (poll-interval 0)
   (progress-marker 1)
   (stalled? 5)
   (start_link 1)))

(defun deadline () 60000)

(defun poll-interval () 1000)

(defun start_link (args)
  (gen_server:start_link 'watchdog args (list)))

(defun child_spec (args)
  (map 'id (maps:get 'id args)
       'start (tuple 'watchdog 'start_link (list args))
       'restart 'transient
       'shutdown 5000
       'type 'worker))

(defun progress-marker
  (('nil)
   (list 0 0))
  ((record)
   (let ((blocks-received (maps:get 'blocks-received record (map)))
          (verified (maps:get 'verified record (list)))
          (active-peers (maps:get 'active-peers record (list))))
     (list (+ (received-blocks blocks-received) (length verified))
           (length active-peers)))))

(defun received-blocks (blocks-received)
  (maps:fold (lambda (_peer blocks total) (+ total (length blocks)))
             0
             blocks-received))

;; ponytail: verified and active-peers are counted as lists; the coordinator
;; (b77.3) is what starts publishing them, and if it publishes sets this
;; becomes sets:size.
(defun stalled? (current previous last-progress-ms now-ms deadline-ms)
  (and (== current previous) (>= (- now-ms last-progress-ms) deadline-ms)))

(defun init (args)
  (let* ((now
           (maps:get 'now args (lambda () (erlang:monotonic_time 'millisecond))))
          (poll-ms (maps:get 'poll-ms args (poll-interval)))
          (state
            (map 'marker (maps:get 'marker args)
                 'now now
                 'deadline-ms (maps:get 'deadline-ms args (deadline))
                 'poll-ms poll-ms
                 'fire (maps:get 'fire args)
                 'previous 'undefined
                 'last-progress-ms (now))))
    (schedule-poll poll-ms)
    (tuple 'ok state)))

(defun handle_info
  (('poll state)
   (let* ((now (funcall (maps:get 'now state)))
           (current (funcall (maps:get 'marker state)))
           (previous (maps:get 'previous state))
           (last-progress
             (if (== current previous)
               (maps:get 'last-progress-ms state)
               now)))
     (if (stalled? current
                   previous
                   last-progress
                   now
                   (maps:get 'deadline-ms state))
       (progn
         (funcall (maps:get 'fire state))
         (tuple 'stop 'normal state))
       (progn
         (schedule-poll (maps:get 'poll-ms state))
         (tuple 'noreply
                (maps:put 'previous
                          current
                          (maps:put 'last-progress-ms last-progress state)))))))
  ((_message state)
   (tuple 'noreply state)))

(defun schedule-poll (poll-ms)
  (erlang:send_after poll-ms (self) 'poll)
  'ok)
