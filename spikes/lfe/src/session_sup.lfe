;;;; Session supervisor, ported from the stop path of
;;;; dev.cljtoc.supervision.session-supervisor.
;;;;
;;;; Each running download gets a worker child and a watchdog child beside
;;;; it, both flat under this supervisor. stop/2 terminates every child; the
;;;; supervisor calls each worker's terminate/2 and waits for it, so the
;;;; paused record is already in the records view when stop returns. The
;;;; supervisor's one_for_one budget replaces the Clojure restart budget, and
;;;; the child's shutdown timeout replaces the shared join deadline.
;;;;
;;;; In the Clojure tree the watchdog is a sibling of the coordinator inside
;;;; one download's subtree, so a restart replaces the whole subtree. This
;;;; spike has no per-download subtree yet, so the watchdog fires directly at
;;;; the worker and both are flat children; the watchdog is one-shot, and a
;;;; restarted worker has no watchdog until the subtree lands.

(defmodule session_sup
  (behaviour supervisor)
  (export
   (init 1)
   (remove_child 3)
   (start_link 1)
   (stop 2)
   (stop_child 3)))

(defun start_link (opts)
  (supervisor:start_link 'session_sup opts))

(defun init (opts)
  (let* ((live (maps:get 'live opts))
          (sink (maps:get 'sink opts))
          (watchdog-opts (maps:get 'watchdog opts (map)))
          (specs
            (child-specs (maps:get 'downloads opts)
                         live
                         sink
                         (self)
                         watchdog-opts)))
    (tuple 'ok (tuple (tuple 'one_for_one 10 10) specs))))

(defun child-specs (downloads live sink sup watchdog-opts)
  (lists:append
    (lists:map
      (lambda (download) (child-spec download live sink sup watchdog-opts))
      downloads)))

(defun child-spec (download live sink sup watchdog-opts)
  (let ((id (maps:get 'id download)))
    (case (maps:get 'state download)
      ('completed
       (download_worker:publish live sink id download)
       (list))
      ('failed
       (download_worker:publish live sink id download)
       (list))
      (_
       (list
         (download_worker:child_spec
           (map 'id id 'download download 'live live 'sink sink))
         (watchdog:child_spec
           (maps:merge
             (map 'id (tuple id 'watchdog)
                  'marker
                  (lambda ()
                    (watchdog:progress-marker (records:record-for live id)))
                  'fire (lambda () (fire sup id)))
             watchdog-opts)))))))

(defun fire (sup id)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'ok)
    (child
     (case (element 2 child)
       (pid (when (is_pid pid))
        (gen_server:cast pid 'watchdog-fired))
       (_other 'ok)))))

(defun stop (sup live)
  (lists:foreach
    (lambda (child) (supervisor:terminate_child sup (element 1 child)))
    (supervisor:which_children sup))
  (tuple 'ok (records:all live)))

(defun stop_child (sup id live)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'nil)
    (_child
     (terminate-child sup (tuple id 'watchdog))
     (supervisor:terminate_child sup id)
     (records:record-for live id))))

(defun remove_child (sup id live)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'ok)
    (_child
     (delete-child sup (tuple id 'watchdog))
     (delete-child sup id)
     (records:forget live id)
     'ok)))

(defun terminate-child (sup child-id)
  (case (lists:keyfind child-id 1 (supervisor:which_children sup))
    ('false 'ok)
    (_child
     (supervisor:terminate_child sup child-id))))

(defun delete-child (sup child-id)
  (case (lists:keyfind child-id 1 (supervisor:which_children sup))
    ('false 'ok)
    (_child
     (supervisor:terminate_child sup child-id)
     (supervisor:delete_child sup child-id)
     'ok)))
