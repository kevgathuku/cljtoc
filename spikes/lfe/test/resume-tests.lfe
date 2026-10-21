(defmodule resume-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun hash20 () (binary:copy (binary 1) 20))

(deftest round-trip
  (let* ((state (map 'info-hash (hash20)
                     'verified (list 0 2 4)
                     'total 5
                     'piece-length 16384))
         (encoded (element 2 (resume:encode state)))
         (decoded (element 2 (resume:decode encoded))))
    (is-equal (maps:get 'info-hash state) (maps:get 'info-hash decoded))
    (is-equal (lists:sort (maps:get 'verified state)) (maps:get 'verified decoded))
    (is-equal 5 (maps:get 'total decoded))
    (is-equal 16384 (maps:get 'piece-length decoded))))

(deftest verified-deduped-and-sorted
  (let* ((state (map 'info-hash (hash20)
                     'verified (list 3 1 3 2)
                     'total 5
                     'piece-length 16384))
         (decoded (element 2 (resume:decode (element 2 (resume:encode state))))))
    (is-equal '(1 2 3) (maps:get 'verified decoded))))

(deftest empty-verified
  (let* ((state (map 'info-hash (hash20)
                     'verified (list)
                     'total 5
                     'piece-length 16384))
         (decoded (element 2 (resume:decode (element 2 (resume:encode state))))))
    (is-equal '() (maps:get 'verified decoded))))

(deftest invalid-info-hash
  (let ((state (map 'info-hash (binary)
               'verified (list)
               'total 5
               'piece-length 16384)))
    (is-equal 'invalid-resume (element 2 (resume:encode state)))))

(deftest invalid-total
  (let ((state (map 'info-hash (hash20)
               'verified (list)
               'total 0
               'piece-length 16384)))
    (is-equal 'invalid-resume (element 2 (resume:encode state)))))

(deftest invalid-piece-length
  (let ((state (map 'info-hash (hash20)
               'verified (list)
               'total 5
               'piece-length 0)))
    (is-equal 'invalid-resume (element 2 (resume:encode state)))))

(deftest invalid-verified
  (let ((state (map 'info-hash (hash20)
               'verified (list 0 -1)
               'total 5
               'piece-length 16384)))
    (is-equal 'invalid-verified (element 2 (resume:encode state)))))

(deftest corrupt-bytes
  (is-equal 'corrupt-resume (element 2 (resume:decode (binary 1 2 3)))))

(deftest non-binary-input
  (is-equal 'invalid-resume (element 2 (resume:decode 42))))

(deftest wrong-version
  (let ((bad (erlang:term_to_binary (map 'v 99 'info-hash (hash20)
                                         'verified (list) 'total 5
                                         'piece-length 16384))))
    (is-equal 'invalid-resume (element 2 (resume:decode bad)))))

(deftest bad-hash-length
  (let ((bad (erlang:term_to_binary (map 'v 1 'info-hash (binary 1 2 3)
                                         'verified (list) 'total 5
                                         'piece-length 16384))))
    (is-equal 'invalid-info-hash (element 2 (resume:decode bad)))))
