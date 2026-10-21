(defmodule session-sup-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun download (id state)
  (map 'id id 'state state))

(defun live-table ()
  (ets:new 'live (list 'set 'public)))

(defun start-session (downloads live)
  (session_sup:start_link (map 'downloads downloads
                               'live live
                               'sink 'undefined)))

(defun pid-for (sup id)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'undefined)
    (child (element 2 child))))

(defun states (records)
  (lists:map (lambda (record) (maps:get 'state record)) records))

(defun live-record (live id)
  (case (ets:lookup live id)
    ((list (tuple _id record)) record)
    (_ 'undefined)))

(defun wait-for-new-pid
  ((_sup _id _old 0) 'undefined)
  ((sup id old attempts)
   (let ((pid (pid-for sup id)))
     (if (and (is_pid pid) (/= pid old))
       pid
       (progn
         (timer:sleep 10)
         (wait-for-new-pid sup id old (- attempts 1)))))))

(deftest stop-pauses-every-child
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (pid-a (pid-for sup "a"))
         (pid-b (pid-for sup "b"))
         ((tuple 'ok records) (session_sup:stop sup live)))
    (is (is_pid pid-a))
    (is (is_pid pid-b))
    (is (not (is_process_alive pid-a)))
    (is (not (is_process_alive pid-b)))
    (is-equal 2 (length records))
    (is-equal (list 'paused 'paused) (states records))))

(deftest stop-child-pauses-only-that-child
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (pid-b (pid-for sup "b"))
         (record (session_sup:stop_child sup "a" live)))
    (is-equal 'paused (maps:get 'state record))
    (is-equal 'undefined (pid-for sup "a"))
    (is-equal pid-b (pid-for sup "b"))
    (is (is_process_alive pid-b))))

(deftest stop-child-unknown-id-is-nil
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)) live)))
    (is-equal 'nil (session_sup:stop_child sup "missing" live))))

(deftest remove-child-forgets-the-record
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (pid-b (pid-for sup "b")))
    (is-equal 'ok (session_sup:remove_child sup "a" live))
    (is-equal 'undefined (pid-for sup "a"))
    (is-equal [] (ets:lookup live "a"))
    (is-equal pid-b (pid-for sup "b"))
    (is (is_process_alive pid-b))))

(deftest crashed-child-restarts-alone
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (old-pid (pid-for sup "a"))
         (sibling (pid-for sup "b")))
    (exit old-pid 'kill)
    (let ((new-pid (wait-for-new-pid sup "a" old-pid 100)))
      (is (is_pid new-pid))
      (is (/= old-pid new-pid))
      (is-equal sibling (pid-for sup "b"))
      (is (is_process_alive sibling))
      (is-equal 'running (maps:get 'state (live-record live "a"))))))

(deftest terminal-download-is-kept-but-not-run
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "done" 'completed)
                                               (download "live" 'downloading))
                                         live))
         (children (supervisor:which_children sup))
         ((tuple 'ok records) (session_sup:stop sup live)))
    (is-equal 1 (length children))
    (is-equal "live" (element 1 (lists:nth 1 children)))
    (is-equal 'completed (maps:get 'state (live-record live "done")))
    (is-equal (list 'completed 'paused) (states records))))
