(defmodule watchdog-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun progress-record (blocks-received verified active-peers)
  (map 'blocks-received blocks-received
       'verified verified
       'active-peers active-peers))

(defun start-watchdog (args)
  (watchdog:start_link (maps:merge (map 'id 'test)
                                   args)))

(defun receive-fired (timeout)
  (receive
    ('fired 'fired)
    (after timeout 'quiet)))

(deftest progress-marker-counts-blocks-and-peers
  (is-equal (list 6 2)
            (watchdog:progress-marker
              (progress-record (map "peer-1" (list 'a 'b 'c) "peer-2" (list 'a))
                      (list 'piece-1 'piece-2)
                      (list "peer-1" "peer-2")))))

(deftest progress-marker-of-a-missing-record-is-zero
  (is-equal (list 0 0) (watchdog:progress-marker 'nil)))

(deftest inactive-state-is-never-a-stall
  (is (not (watchdog:stalled? (list 1 1) 'undefined 0 100000 100))))

(deftest a-moved-marker-is-never-a-stall
  (is (not (watchdog:stalled? (list 2 1) (list 1 1) 0 100000 100))))

(deftest time-below-the-deadline-is-never-a-stall
  (is (not (watchdog:stalled? (list 1 1) (list 1 1) 0 99 100))))

(deftest the-deadline-is-reached-exactly
  (is (watchdog:stalled? (list 1 1) (list 1 1) 0 100 100)))

(deftest a-watchdog-fires-when-the-marker-never-moves
  (let* ((parent (self))
         ((tuple 'ok pid) (start-watchdog
                            (map 'marker (lambda () (list 0 0))
                                 'fire (lambda () (erlang:send parent 'fired))
                                 'deadline-ms 20
                                 'poll-ms 5))))
    (is-equal 'fired (receive-fired 1000))
    (timer:sleep 20)
    (is (not (is_process_alive pid)))))

(deftest a-watchdog-stays-quiet-while-the-marker-moves
  (let* ((parent (self))
         ((tuple 'ok pid) (start-watchdog
                            (map 'marker (lambda () (erlang:unique_integer))
                                 'fire (lambda () (erlang:send parent 'fired))
                                 'deadline-ms 20
                                 'poll-ms 5))))
    (is-equal 'quiet (receive-fired 200))
    (is (is_process_alive pid))
    (gen_server:stop pid)))

(deftest a-frozen-clock-never-fires
  (let* ((parent (self))
         ((tuple 'ok pid) (start-watchdog
                            (map 'marker (lambda () (list 0 0))
                                 'now (lambda () 1000)
                                 'fire (lambda () (erlang:send parent 'fired))
                                 'deadline-ms 20
                                 'poll-ms 5))))
    (is-equal 'quiet (receive-fired 100))
    (gen_server:stop pid)))
