;;;; One download worker, ported from the stop path of
;;;; dev.cljtoc.supervision.session-supervisor.
;;;;
;;;; The Clojure supervisor cancels a future, waits on a done promise with a
;;;; shared deadline, and catches InterruptedException to persist a paused
;;;; record. Under OTP the supervisor calls terminate/2 synchronously, so the
;;;; pause happens in the callback and the supervisor's shutdown timeout is
;;;; the join bound.

(defmodule download_worker
  (behaviour gen_server)
  (export
   (child_spec 1)
   (code_change 3)
   (handle_call 3)
   (handle_cast 2)
   (handle_info 2)
   (init 1)
   (publish 4)
   (start_link 1)
   (terminate 2)))

(defun start_link (args)
  (gen_server:start_link 'download_worker args (list)))

(defun child_spec (args)
  (map 'id (maps:get 'id args)
       'start (tuple 'download_worker 'start_link (list args))
       'restart 'transient
       'shutdown 5000
       'type 'worker))

(defun publish (live sink id record)
  (records:put live record)
  (case (is_pid sink)
    ('true
     (erlang:send sink (tuple 'record id record)))
    ('false 'ok))
  'ok)

(defun init (args)
  (process_flag 'trap_exit 'true)
  (let* ((id (maps:get 'id args))
          (live (maps:get 'live args))
          (sink (maps:get 'sink args))
          (record (maps:put 'state 'running (maps:get 'download args))))
    (publish live sink id record)
    (tuple 'ok (map 'id id 'live live 'sink sink 'record record))))

(defun handle_call
  (('record _from state)
   (tuple 'reply (maps:get 'record state) state))
  ((_request _from state)
   (tuple 'reply 'ok state)))

(defun handle_cast
  (('watchdog-fired state)
   (tuple 'stop 'watchdog-fired state))
  (((tuple 'progress record) state)
   (publish (maps:get 'live state)
            (maps:get 'sink state)
            (maps:get 'id state)
            record)
   (tuple 'noreply (maps:put 'record record state)))
  ((_message state)
   (tuple 'noreply state)))

(defun handle_info (_message state)
  (tuple 'noreply state))

(defun terminate (reason state)
  (if (pause-reason? reason)
    (publish (maps:get 'live state)
             (maps:get 'sink state)
             (maps:get 'id state)
             (maps:put 'state 'paused (maps:get 'record state)))
    'ok)
  'ok)

(defun code_change (_old-version state _extra)
  (tuple 'ok state))

(defun pause-reason?
  (('normal) 'true)
  (('shutdown) 'true)
  (((tuple 'shutdown _)) 'true)
  ((_) 'false))
