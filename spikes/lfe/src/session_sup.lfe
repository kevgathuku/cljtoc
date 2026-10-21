;;;; Session supervisor, ported from the stop path of
;;;; dev.cljtoc.supervision.session-supervisor.
;;;;
;;;; One child per download. stop/2 terminates every child; the supervisor
;;;; calls each worker's terminate/2 and waits for it, so the paused record
;;;; is already in the live table when stop returns. The supervisor's
;;;; one_for_one budget replaces the Clojure restart budget, and the child's
;;;; shutdown timeout replaces the shared join deadline.

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
          (specs (child-specs (maps:get 'downloads opts) live sink)))
    (tuple 'ok (tuple (tuple 'one_for_one 10 10) specs))))

(defun child-specs (downloads live sink)
  (lists:filtermap
    (lambda (download) (child-spec download live sink))
    downloads))

(defun child-spec (download live sink)
  (let ((id (maps:get 'id download)))
    (case (maps:get 'state download)
      ('completed
       (download_worker:publish live sink id download)
       'false)
      ('failed
       (download_worker:publish live sink id download)
       'false)
      (_
       (tuple 'true
              (download_worker:child_spec
                (map 'id id 'download download 'live live 'sink sink)))))))

(defun stop (sup live)
  (lists:foreach
    (lambda (child) (supervisor:terminate_child sup (element 1 child)))
    (supervisor:which_children sup))
  (tuple 'ok (records live)))

(defun stop_child (sup id live)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'nil)
    (_child
     (supervisor:terminate_child sup id)
     (case (ets:lookup live id)
       ((list (tuple _id record)) record)
       (_ 'nil)))))

(defun remove_child (sup id live)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'ok)
    (_child
     (supervisor:terminate_child sup id)
     (supervisor:delete_child sup id)
     (ets:delete live id)
     'ok)))

(defun records (live)
  (lists:sort
    (lambda (left right) (=< (maps:get 'id left) (maps:get 'id right)))
    (lists:map (lambda (pair) (element 2 pair)) (ets:tab2list live))))
