(defmodule records-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun download (id state)
  (map 'id id 'state state))

(deftest create-seeds-the-view
  (let ((table (records:create (list (download "a" 'paused)))))
    (is-equal 'paused (maps:get 'state (records:record-for table "a")))))

(deftest record-for-unknown-id-is-nil
  (let ((table (records:create '())))
    (is-equal 'nil (records:record-for table "missing"))))

(deftest put-adds-then-replaces
  (let ((table (records:create '())))
    (is-equal 'ok (records:put table (download "a" 'running)))
    (is-equal 'running (maps:get 'state (records:record-for table "a")))
    (records:put table (download "a" 'paused))
    (is-equal 'paused (maps:get 'state (records:record-for table "a")))
    (is-equal 1 (length (records:all table)))))

(deftest all-is-sorted-by-id
  (let ((table (records:create (list (download "b" 'running)
                                     (download "a" 'paused)))))
    (is-equal (list "a" "b")
              (lists:map (lambda (record) (maps:get 'id record))
                         (records:all table)))))

(deftest forget-drops-one
  (let ((table (records:create (list (download "a" 'running)
                                     (download "b" 'running)))))
    (is-equal 'ok (records:forget table "a"))
    (is-equal 'nil (records:record-for table "a"))
    (is-equal 1 (length (records:all table)))))
