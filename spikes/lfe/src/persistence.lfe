;;;; Periodic persistence timer, ported from dev.cljtoc.daemon.persistence.
;;;;
;;;; A gen_server owns the tick via erlang:send_after. The pure core is
;;;; save-all/2: it persists every record a save function hands back and
;;;; returns only the failures, each carrying the id it belongs to, so the
;;;; caller reports them and the next tick retries. A save that throws is
;;;; caught per record, so a full disk or an unencodable record never takes
;;;; the session down.

(defmodule persistence
  (behaviour gen_server)
  (export
   (handle_call 3)
   (handle_info 2)
   (init 1)
   (save-all 2)
   (start_link 1)
   (stop 1)))

(defun default-interval-ms () 60000)

(defun default-stop-timeout-ms () 10000)

(defun save-all (save-fn records)
  (lists:foldl
    (lambda (record acc)
      (let ((id (maps:get 'id record 'unknown)))
        (case (try-save save-fn record)
          ((tuple 'ok _) acc)
          ((tuple 'error reason 'message message)
           (cons (map 'id id 'error reason 'message message) acc))
          ((tuple 'error reason)
           (cons (map 'id id 'error reason) acc)))))
    (list)
    records))

(defun try-save (save-fn record)
  (try
    (funcall save-fn record)
    (catch
      ((tuple _ _ _)
       (tuple 'error 'save-error 'message "save threw")))))

(defun report-failures (failures)
  (lists:foreach
    (lambda (failure)
      (io:format "[persist] WARNING: could not save ~p: ~p~n"
                 (list (maps:get 'id failure 'unknown)
                       (maps:get 'message failure
                                 (maps:get 'error failure 'unknown)))))
    failures)
  'ok)

(defun start_link (args)
  (gen_server:start_link 'persistence args (list)))

(defun init (args)
  (let* ((interval-ms
           (maps:get 'interval-ms
                     args
                     (default-interval-ms)))
          (state
            (map 'save-fn (maps:get 'save-fn args)
                 'records-fn (maps:get 'records-fn args)
                 'interval-ms interval-ms
                 'stopped 'false)))
    (schedule-tick interval-ms)
    (tuple 'ok state)))

(defun handle_call
  (('stop _from state)
   (let* ((records (funcall (maps:get 'records-fn state)))
           (failures (save-all (maps:get 'save-fn state) records)))
     (report-failures failures))
   (tuple 'reply 'ok (maps:put 'stopped 'true state)))
  ((_request _from state)
   (tuple 'reply 'ok state)))

(defun handle_info
  (('tick state)
   (if (== (maps:get 'stopped state) 'true)
     (tuple 'noreply state)
     (progn
       (let* ((records (funcall (maps:get 'records-fn state)))
               (failures (save-all (maps:get 'save-fn state) records)))
         (report-failures failures))
       (schedule-tick (maps:get 'interval-ms state))
       (tuple 'noreply state))))
  ((_message state)
   (tuple 'noreply state)))

(defun schedule-tick (interval-ms)
  (erlang:send_after interval-ms (self) 'tick)
  'ok)

(defun stop (pid)
  (gen_server:call pid
                   'stop
                   (default-stop-timeout-ms)))
