(defmodule torrent-pieces-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun elixir-ebin ()
  (case (os:getenv "ELIXIR_EBIN")
    ('false "/Users/kevin/code/elixir/torrent_client/_build/dev/lib/torrent_client/ebin")
    (path path)))

(defun ensure-elixir-domain ()
  (case (code:which 'Elixir.Torrent)
    ('non_existing (code:add_patha (elixir-ebin)))
    (_ 'ok)))

(defun set-of (list) (lists:sort (sets:to_list (sets:from_list list))))

(defun needed (state) (set-of (sets:to_list (maps:get 'needed state))))
(defun in-flight (state) (set-of (sets:to_list (maps:get 'in-flight state))))
(defun verified (state) (set-of (sets:to_list (maps:get 'verified state))))

(defun verified-state (total indices)
  (lists:foldl
   (lambda (idx state)
     (element 2 (torrent_pieces:mark-verified
                 (element 2 (torrent_pieces:mark-in-flight state idx))
                 idx)))
   (torrent_pieces:initial-piece-state total)
   indices))

(deftest initial-piece-state
  (let ((state (torrent_pieces:initial-piece-state 3)))
    (is-equal 3 (maps:get 'total-pieces state))
    (is-equal '(0 1 2) (needed state))
    (is-equal '() (in-flight state))
    (is-equal '() (verified state))
    (is-equal 3 (torrent_pieces:needed-count state))
    (is-equal 0 (torrent_pieces:in-flight-count state))
    (is-equal 0 (torrent_pieces:verified-count state))))

(deftest mark-in-flight
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (result (torrent_pieces:mark-in-flight state 2))
         (state2 (element 2 result)))
    (is-equal 'ok (element 1 result))
    (is-equal '(0 1 3 4) (needed state2))
    (is-equal '(2) (in-flight state2))
    (is-equal 4 (torrent_pieces:needed-count state2))
    (is-equal 1 (torrent_pieces:in-flight-count state2))
    (is-equal '(0 1 2 3 4) (needed state))
    (is-equal 5 (torrent_pieces:needed-count state))))

(deftest mark-verified
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 2)))
         (result (torrent_pieces:mark-verified state2 2))
         (state3 (element 2 result)))
    (is-equal 'ok (element 1 result))
    (is-equal '() (in-flight state3))
    (is-equal '(2) (verified state3))
    (is-equal 1 (torrent_pieces:verified-count state3))))

(deftest requeue-piece
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 2)))
         (result (torrent_pieces:requeue-piece state2 2))
         (state3 (element 2 result)))
    (is-equal 'ok (element 1 result))
    (is-equal '() (in-flight state3))
    (is-equal '(0 1 2 3 4) (needed state3))
    (is-equal 5 (torrent_pieces:needed-count state3))))

(deftest requeue-verified
  (let* ((state (verified-state 5 '(2)))
         (result (torrent_pieces:requeue-verified state 2))
         (state2 (element 2 result)))
    (is-equal 'ok (element 1 result))
    (is-equal '() (verified state2))
    (is-equal '(0 1 2 3 4) (needed state2))
    (is-equal 5 (torrent_pieces:needed-count state2))))

(deftest invalid-transitions
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 2)))
         (state3 (element 2 (torrent_pieces:mark-verified state2 2)))
         (already-in-flight (torrent_pieces:mark-in-flight state2 2))
         (already-verified (torrent_pieces:mark-in-flight state3 2))
         (not-in-flight (torrent_pieces:mark-verified state 2))
         (not-in-flight-requeue (torrent_pieces:requeue-piece state 2))
         (not-verified (torrent_pieces:requeue-verified state 2)))
    (is-equal 'invalid-transition (maps:get 'error already-in-flight))
    (is (is_list (maps:get 'message already-in-flight)))
    (is-equal 'invalid-transition (maps:get 'error already-verified))
    (is-equal 'invalid-transition (maps:get 'error not-in-flight))
    (is-equal 'invalid-transition (maps:get 'error not-in-flight-requeue))
    (is-equal 'invalid-transition (maps:get 'error not-verified))))

(deftest complete?
  (is-equal 'false (torrent_pieces:complete? (torrent_pieces:initial-piece-state 3)))
  (is-equal 'false (torrent_pieces:complete? (verified-state 3 '(0))))
  (is-equal 'true (torrent_pieces:complete? (verified-state 3 '(0 1 2)))))

(deftest select-piece-rarest
  (let ((state (torrent_pieces:initial-piece-state 10))
        (peer-av (sets:from_list '(2 5)))
        (all-peers (list (sets:from_list '(0 2 5))
                         (sets:from_list '(2 3))
                         (sets:from_list '(0 1 2)))))
    (is-equal (tuple 'ok 5)
              (torrent_pieces:select-piece state peer-av all-peers))))

(deftest select-piece-tie-break
  (let ((state (torrent_pieces:initial-piece-state 10))
        (peer-av (sets:from_list '(3 7)))
        (all-peers (list (sets:from_list '(3 7))
                         (sets:from_list '(3 7)))))
    (is-equal (tuple 'ok 3)
              (torrent_pieces:select-piece state peer-av all-peers))))

(deftest select-piece-no-overlap
  (let ((state (verified-state 5 '(0 1 2 3 4))))
    (is-equal (tuple 'ok 'nil)
              (torrent_pieces:select-piece state
                                           (sets:from_list '(0 1 2 3 4))
                                           (list (sets:from_list '(0 1 2 3 4)))))
    (is-equal (tuple 'ok 'nil)
              (torrent_pieces:select-piece (torrent_pieces:initial-piece-state 5)
                                           (sets:new)
                                           '()))))

(deftest select-piece-excludes-in-flight
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 0)))
         (state3 (element 2 (torrent_pieces:mark-in-flight state2 1)))
         (result (torrent_pieces:select-piece state3
                                              (sets:from_list '(0 1 2 3))
                                              (list (sets:from_list '(0 1 2 3))))))
    (is-equal (tuple 'ok 2) result)))

(deftest piece-length
  (is-equal 524288 (torrent_pieces:piece-length 0 524288 1056768))
  (is-equal 524288 (torrent_pieces:piece-length 1 524288 1056768))
  (is-equal 8192 (torrent_pieces:piece-length 2 524288 1056768))
  (is-equal 524288 (torrent_pieces:piece-length 1 524288 1048576))
  (is-equal 1 (torrent_pieces:piece-length 0 16384 1))
  (is-equal 0 (torrent_pieces:piece-length 5 524288 1048576))
  (is-equal 0 (torrent_pieces:piece-length 9007199254740993 16384 1000)))

(deftest piece-blocks
  (let* ((result (torrent_pieces:piece-blocks 0 524288 1073741824))
         (blocks (element 2 result)))
    (is-equal 32 (length blocks))
    (is-equal '(16384) (lists:usort (lists:map
                                     (lambda (b) (maps:get 'length b))
                                     blocks)))
    (is-equal 0 (maps:get 'offset (lists:nth 1 blocks)))
    (is-equal 507904 (maps:get 'offset (lists:last blocks))))
  (let* ((result (torrent_pieces:piece-blocks 0 20000 20000))
         (blocks (element 2 result)))
    (is-equal 2 (length blocks))
    (is-equal 16384 (maps:get 'length (lists:nth 1 blocks)))
    (is-equal 3616 (maps:get 'length (lists:nth 2 blocks)))
    (is-equal 16384 (maps:get 'offset (lists:nth 2 blocks))))
  (let* ((result (torrent_pieces:piece-blocks 2 524288 1056768))
         (blocks (element 2 result)))
    (is-equal 1 (length blocks))
    (is-equal (map 'piece-index 2 'offset 0 'length 8192) (lists:nth 1 blocks)))
  (let* ((result (torrent_pieces:piece-blocks 0 16384 1))
         (blocks (element 2 result)))
    (is-equal 1 (length blocks))
    (is-equal 1 (maps:get 'length (lists:nth 1 blocks)))))

(deftest piece-blocks-out-of-range
  (let ((result (torrent_pieces:piece-blocks 5 524288 1048576)))
    (is-equal 'invalid-input (maps:get 'error result))
    (is (is_list (maps:get 'message result))))
  (let ((result (torrent_pieces:piece-blocks -1 524288 1048576)))
    (is-equal 'invalid-input (maps:get 'error result))))

(deftest piece-blocks-last-piece-past-two-to-53
  (let* ((total-length 9007199254740993)
         (result (torrent_pieces:piece-blocks 4503599627370496 2 total-length)))
    (is-equal 'ok (element 1 result))
    (is-equal 1 (length (element 2 result))) ))

(deftest verify-piece-modes
  (ensure-elixir-domain)
  (let* ((data (list_to_binary (list 1 2 3 4 5)))
         (hash (apply 'Elixir.Utils 'sha_sum (list data))))
    (is-equal (tuple 'ok 5) (torrent_pieces:verify-piece 5 data hash))
    (is-equal 'hash-mismatch
              (maps:get 'error
                        (torrent_pieces:verify-piece 5 (list_to_binary (list 1 2 3 4 99)) hash)))
    (is-equal 'invalid-input
              (maps:get 'error
                        (torrent_pieces:verify-piece 0 (list_to_binary '()) (list_to_binary (lists:duplicate 20 0)))))
    (is-equal 'invalid-input
              (maps:get 'error
                        (torrent_pieces:verify-piece 0 (list_to_binary (list 1)) (list_to_binary (list 1)))))
    (is-equal 'invalid-input
              (maps:get 'error
                        (torrent_pieces:verify-piece 0 (list_to_binary (list 1)) (list_to_binary (lists:duplicate 21 0)))))))

(deftest endgame
  (let* ((state (verified-state 5 '(0 1 2)))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 3))))
    (is-equal 'true (torrent_pieces:endgame? state2 20))
    (is-equal 'true (torrent_pieces:endgame? state2 2))
    (is-equal 'false (torrent_pieces:endgame? (torrent_pieces:initial-piece-state 50) 20))
    (is-equal 'true (torrent_pieces:endgame? (verified-state 3 '(0 1 2)) 0))))

(deftest select-pieces-endgame
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 0))))
    (is-equal (tuple 'ok '(0 2 4))
              (torrent_pieces:select-pieces-endgame
               state2 (sets:from_list '(0 2 4 99))))
    (is-equal (tuple 'ok '())
              (torrent_pieces:select-pieces-endgame
               (verified-state 3 '(0 1 2)) (sets:from_list '(0 1 2))))))

(deftest assemble-piece-malformed
  (is-equal 'invalid-block
            (maps:get 'error (torrent_pieces:assemble-piece (list (map 'offset 0)) 4)))
  (is-equal 'invalid-block
            (maps:get 'error (torrent_pieces:assemble-piece (list 42) 4)))
  (is-equal 'invalid-block
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'data (list_to_binary (list 1)))) 4)))
  (is-equal 'invalid-block
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset 0 'data "abcd")) 4)))
  (is-equal 'block-overflow
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset 0 'data (list_to_binary (lists:duplicate 8 0)))) 4)))
  (is-equal 'invalid-block
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset -1 'data (list_to_binary (list 1)))) 4)))
  (is-equal 'invalid-input
            (maps:get 'error (torrent_pieces:assemble-piece '() 4)))
  (is-equal 'gap-in-blocks
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset 2 'data (list_to_binary (list 3 4)))) 4)))
  (is-equal 'incomplete-piece
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset 0 'data (list_to_binary (list 1 2)))) 4)))
  (is-equal (tuple 'ok (list_to_binary (list 1 2 3 4)))
            (torrent_pieces:assemble-piece
             (list (map 'offset 0 'data (list_to_binary (list 1 2)))
                   (map 'offset 2 'data (list_to_binary (list 3 4)))) 4)))
