(defmodule persistence-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun save-ok (record) (tuple 'ok 'saved))

(defun save-fail (record) (tuple 'error 'disk-full))

(defun save-throw (record) (erlang:error 'boom))

(defun records () (list (map 'id "a") (map 'id "b")))

(deftest save-all-all-ok
  (is-equal '() (persistence:save-all (lambda (r) (save-ok r)) (records))))

(deftest save-all-some-fail
  (let ((failures (persistence:save-all
                    (lambda (r)
                      (case (maps:get 'id r)
                        ("a" (save-ok r))
                        (_ (save-fail r))))
                    (records))))
    (is-equal 1 (length failures))
    (is-equal "b" (maps:get 'id (car failures)))
    (is-equal 'disk-full (maps:get 'error (car failures)))))

(deftest save-all-catches-throw
  (let ((failures (persistence:save-all
                    (lambda (r)
                      (case (maps:get 'id r)
                        ("a" (save-ok r))
                        (_ (save-throw r))))
                    (records))))
    (is-equal 1 (length failures))
    (is-equal "b" (maps:get 'id (car failures)))
    (is-equal 'save-error (maps:get 'error (car failures)))))

(deftest save-all-empty
  (is-equal '() (persistence:save-all (lambda (r) (save-ok r)) (list))))

(deftest timer-fires
  (let* ((counter (ets:new 'counter (list 'set 'public)))
          (_ (ets:insert counter (tuple 'n 0)))
          (save-fn (lambda (r)
                     (ets:update_counter counter 'n 1)
                     (tuple 'ok 'saved)))
          ((tuple 'ok pid) (persistence:start_link
                             (map 'save-fn save-fn
                                  'records-fn (lambda () (list (map 'id "a")))
                                  'interval-ms 50))))
    (timer:sleep 120)
    (is (>= (element 2 (hd (ets:lookup counter 'n))) 1))
    (persistence:stop pid)))

(deftest stop-does-final-save
  (let* ((counter (ets:new 'counter (list 'set 'public)))
          (_ (ets:insert counter (tuple 'n 0)))
          (save-fn (lambda (r)
                     (ets:update_counter counter 'n 1)
                     (tuple 'ok 'saved)))
          ((tuple 'ok pid) (persistence:start_link
                             (map 'save-fn save-fn
                                  'records-fn (lambda () (list (map 'id "a")))
                                  'interval-ms 100000))))
    (persistence:stop pid)
    (is (>= (element 2 (hd (ets:lookup counter 'n))) 1))))
