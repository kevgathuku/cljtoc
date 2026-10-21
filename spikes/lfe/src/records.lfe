;;;; The daemon's live view of its torrents: one record per download id.
;;;; Ported from dev.cljtoc.daemon.records.
;;;;
;;;; The Clojure view is an atom, because the RPC handlers and the session's
;;;; progress both update it from their own threads. The Erlang equivalent of
;;;; that shared, concurrently-written view is a public ETS table, so the
;;;; table handle plays the atom's role and travels the same way: created
;;;; once and passed to the session supervisor and the workers.

(defmodule records
  (export
   (all 1)
   (create 1)
   (forget 2)
   (put 2)
   (record-for 2)))

(defun create (records)
  (let ((table (ets:new 'records (list 'set 'public))))
    (lists:foreach
      (lambda (record) (ets:insert table (tuple (maps:get 'id record) record)))
      records)
    table))

(defun record-for (table id)
  (case (ets:lookup table id)
    ((list (tuple _id record)) record)
    (_ 'nil)))

(defun put (table record)
  (ets:insert table (tuple (maps:get 'id record) record))
  'ok)

(defun forget (table id)
  (ets:delete table id)
  'ok)

;; Sorted by id for a stable listing; the Clojure view is unordered.
(defun all (table)
  (lists:sort
    (lambda (left right) (=< (maps:get 'id left) (maps:get 'id right)))
    (lists:map (lambda (pair) (element 2 pair)) (ets:tab2list table))))
