(defmodule session-sup-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun download (id state)
  (map 'id id 'state state))

(defun start-session (downloads live)
  (session_sup:start_link (map 'downloads downloads
                               'live live
                               'sink 'undefined)))

(defun start-session-with-watchdog (downloads live watchdog-opts)
  (session_sup:start_link (map 'downloads downloads
                               'live live
                               'sink 'undefined
                               'watchdog watchdog-opts)))

(defun watchdog-id (id) (tuple id 'watchdog))

(defun has-child? (sup child-id)
  (case (lists:keyfind child-id 1 (supervisor:which_children sup))
    ('false 'false)
    (child (is_pid (element 2 child)))))

(defun pid-for (sup id)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'undefined)
    (child (element 2 child))))

(defun states (records)
  (lists:map (lambda (record) (maps:get 'state record)) records))

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
  (let* ((live (records:create '()))
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
  (let* ((live (records:create '()))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (pid-b (pid-for sup "b"))
         (record (session_sup:stop_child sup "a" live)))
    (is-equal 'paused (maps:get 'state record))
    (is-equal 'undefined (pid-for sup "a"))
    (is (not (has-child? sup (watchdog-id "a"))))
    (is (has-child? sup (watchdog-id "b")))
    (is-equal pid-b (pid-for sup "b"))
    (is (is_process_alive pid-b))))

(deftest stop-child-unknown-id-is-nil
  (let* ((live (records:create '()))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)) live)))
    (is-equal 'nil (session_sup:stop_child sup "missing" live))))

(deftest remove-child-forgets-the-record
  (let* ((live (records:create '()))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (pid-b (pid-for sup "b")))
    (is-equal 'ok (session_sup:remove_child sup "a" live))
    (is-equal 'undefined (pid-for sup "a"))
    (is-equal 'nil (records:record-for live "a"))
    (is-equal pid-b (pid-for sup "b"))
    (is (is_process_alive pid-b))))

(deftest crashed-child-restarts-alone
  (let* ((live (records:create '()))
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
      (is-equal 'running (maps:get 'state (records:record-for live "a"))))))

(deftest terminal-download-is-kept-but-not-run
  (let* ((live (records:create '()))
         ((tuple 'ok sup) (start-session (list (download "done" 'completed)
                                               (download "live" 'downloading))
                                         live))
         (children (supervisor:which_children sup))
         (keeps-live-only? (and (has-child? sup "live")
                                (has-child? sup (watchdog-id "live"))
                                (not (has-child? sup "done"))))
         ((tuple 'ok records) (session_sup:stop sup live)))
    (is-equal 2 (length children))
    (is keeps-live-only?)
    (is-equal 'completed (maps:get 'state (records:record-for live "done")))
    (is-equal (list 'completed 'paused) (states records))))

(deftest a-stalled-download-restarts
  (let* ((live (records:create '()))
         ((tuple 'ok sup) (start-session-with-watchdog
                            (list (download "a" 'downloading))
                            live
                            (map 'deadline-ms 20 'poll-ms 5)))
         (old-pid (pid-for sup "a")))
    (let ((new-pid (wait-for-new-pid sup "a" old-pid 100)))
      (is (is_pid new-pid))
      (is (/= old-pid new-pid))
      (is-equal 'running (maps:get 'state (records:record-for live "a"))))
    (session_sup:stop sup live)))

(deftest remove-child-removes-the-watchdog-too
  (let* ((live (records:create '()))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live)))
    (is-equal 'ok (session_sup:remove_child sup "a" live))
    (is (not (has-child? sup "a")))
    (is (not (has-child? sup (watchdog-id "a"))))
    (is (has-child? sup "b"))
    (is (has-child? sup (watchdog-id "b")))
    (session_sup:stop sup live)))
