;;;; Piece management ported from dev.cljtoc.domain.pieces (ADR-0005).
;;;;
;;;; Pure functions only: state transitions return new maps, selection is
;;;; rarest-first with a lowest-index tie break, and verification calls the
;;;; existing Elixir Torrent.verify_hash?/2 through plain BEAM interop.

(defmodule torrent_pieces
  (export
   (assemble-piece 2)
   (complete? 1)
   (endgame? 2)
   (in-flight-count 1)
   (initial-piece-state 1)
   (mark-in-flight 2)
   (mark-verified 2)
   (needed-count 1)
   (piece-blocks 3)
   (piece-length 3)
   (requeue-piece 2)
   (requeue-verified 2)
   (select-piece 3)
   (select-pieces-endgame 2)
   (verified-count 1)
   (verify-piece 3)))

(defun block-size () 16384)

(defun piece-error (error-kw message)
  (map 'error error-kw 'message message))

(defun initial-piece-state (total-pieces)
  (map 'total-pieces total-pieces
       'needed (sets:from_list (lists:seq 0 (- total-pieces 1)))
       'in-flight (sets:new)
       'verified (sets:new)))

(defun needed-count (state)
  (sets:size (maps:get 'needed state)))

(defun in-flight-count (state)
  (sets:size (maps:get 'in-flight state)))

(defun verified-count (state)
  (sets:size (maps:get 'verified state)))

(defun complete? (state)
  (== (sets:size (maps:get 'verified state)) (maps:get 'total-pieces state)))

(defun mark-in-flight (state piece-index)
  (let ((needed (maps:get 'needed state)))
    (if (sets:is_element piece-index needed)
      (tuple 'ok
             (maps:put 'needed (sets:del_element piece-index needed)
                       (maps:put 'in-flight
                                 (sets:add_element piece-index
                                                   (maps:get 'in-flight state))
                                 state)))
      (piece-error 'invalid-transition
                   (lists:flatten
                     (io_lib:format
                       "Piece ~p is not in needed; cannot mark in-flight"
                       (list piece-index)))))))

(defun mark-verified (state piece-index)
  (let ((in-flight (maps:get 'in-flight state)))
    (if (sets:is_element piece-index in-flight)
      (tuple 'ok
             (maps:put 'in-flight (sets:del_element piece-index in-flight)
                       (maps:put 'verified
                                 (sets:add_element piece-index
                                                   (maps:get 'verified state))
                                 state)))
      (piece-error 'invalid-transition
                   (lists:flatten
                     (io_lib:format
                       "Piece ~p is not in-flight; cannot mark verified"
                       (list piece-index)))))))

(defun requeue-piece (state piece-index)
  (let ((in-flight (maps:get 'in-flight state)))
    (if (sets:is_element piece-index in-flight)
      (tuple 'ok
             (maps:put 'in-flight (sets:del_element piece-index in-flight)
                       (maps:put 'needed
                                 (sets:add_element piece-index
                                                   (maps:get 'needed state))
                                 state)))
      (piece-error 'invalid-transition
                   (lists:flatten
                     (io_lib:format
                       "Piece ~p is not in-flight; cannot requeue"
                       (list piece-index)))))))

(defun requeue-verified (state piece-index)
  (let ((verified (maps:get 'verified state)))
    (if (sets:is_element piece-index verified)
      (tuple 'ok
             (maps:put 'verified (sets:del_element piece-index verified)
                       (maps:put 'needed
                                 (sets:add_element piece-index
                                                   (maps:get 'needed state))
                                 state)))
      (piece-error 'invalid-transition
                   (lists:flatten
                     (io_lib:format
                       "Piece ~p is not verified; cannot requeue"
                       (list piece-index)))))))

(defun select-piece (piece-state peer-available all-peers-available)
  (let* ((needed (maps:get 'needed piece-state))
          (candidates (sets:intersection (as-set peer-available) needed)))
    (if (== 0 (sets:size candidates))
      (tuple 'ok 'nil)
      (let* ((ranked
               (lists:map
                 (lambda (piece-index)
                   (tuple (held-by-count piece-index all-peers-available)
                          piece-index))
                 (sets:to_list candidates)))
              (rarest (lists:min ranked)))
        (tuple 'ok (element 2 rarest))))))

(defun held-by-count (piece-index all-peers-available)
  (lists:foldl
    (lambda (peer acc)
      (if (sets:is_element piece-index (as-set peer))
        (+ acc 1)
        acc))
    0
    all-peers-available))

(defun as-set
  ((list) (when (is_list list))
   (sets:from_list list))
  ((other) other))

(defun piece-length (piece-index standard-piece-length total-length)
  (let* ((start (* piece-index standard-piece-length))
          (finish (min (+ start standard-piece-length) total-length)))
    (max 0 (- finish start))))

(defun total-piece-count (total-length standard-piece-length)
  (+ (div (- total-length 1) standard-piece-length) 1))

(defun piece-blocks (piece-index standard-piece-length total-length)
  (let ((total-pieces (total-piece-count total-length standard-piece-length)))
    (if (or (< piece-index 0) (>= piece-index total-pieces))
      (piece-error 'invalid-input
                   (lists:flatten
                     (io_lib:format
                       "Piece index ~p is out of range [0, ~p)"
                       (list piece-index total-pieces))))
      (let* ((piece-size
               (piece-length piece-index standard-piece-length total-length))
              (blocks (block-loop piece-index piece-size 0 '())))
        (tuple 'ok (lists:reverse blocks))))))

(defun block-loop (piece-index piece-size offset acc)
  (if (>= offset piece-size)
    acc
    (let ((block-length (min (block-size) (- piece-size offset))))
      (block-loop piece-index
                  piece-size
                  (+ offset block-length)
                  (cons
                    (map 'piece-index piece-index
                         'offset offset
                         'length block-length)
                    acc)))))

(defun verify-piece (piece-index assembled-bytes expected-hash)
  (cond
    ((not (is_binary assembled-bytes))
     (piece-error 'invalid-input "assembled-bytes must be a binary"))
    ((== 0 (byte_size assembled-bytes))
     (piece-error 'invalid-input "assembled-bytes must not be empty"))
    ((not (is_binary expected-hash))
     (piece-error 'invalid-input
                  "expected-hash must be exactly 20 bytes, got nil"))
    ((not (== 20 (byte_size expected-hash)))
     (piece-error 'invalid-input
                  (lists:flatten
                    (io_lib:format
                      "expected-hash must be exactly 20 bytes, got ~p"
                      (list (byte_size expected-hash))))))
    ((apply 'Elixir.Torrent 'verify_hash? (list assembled-bytes expected-hash))
     (tuple 'ok piece-index))
    ('true
     (map 'error 'hash-mismatch
          'piece-index piece-index
          'message
          (lists:flatten
            (io_lib:format "Piece ~p hash mismatch" (list piece-index)))))))

(defun block-shape-error (block expected-length)
  (cond
    ((not (is_map block))
     (piece-error 'invalid-block
                  (lists:flatten
                    (io_lib:format "Block is not a map: ~p" (list block)))))
    ('true
     (let* ((offset (maps:get 'offset block 'undefined))
             (data (maps:get 'data block 'undefined)))
       (cond
         ((not (and (is_integer offset) (>= offset 0)))
          (piece-error 'invalid-block
                       (lists:flatten
                         (io_lib:format
                           "Block offset must be a non-negative integer, got ~p"
                           (list offset)))))
         ((not (is_binary data))
          (piece-error 'invalid-block
                       (lists:flatten
                         (io_lib:format
                           "Block at offset ~p carries no byte array"
                           (list offset)))))
         ((> (+ offset (byte_size data)) expected-length)
          (piece-error 'block-overflow
                       (lists:flatten
                         (io_lib:format
                           "Block at offset ~p with ~p bytes runs past piece length ~p"
                           (list offset (byte_size data) expected-length)))))
         ('true 'ok))))))

(defun first-shape-error (blocks expected-length)
  (case blocks
    ('() 'none)
    ((cons block rest)
     (case (block-shape-error block expected-length)
       ('ok
        (first-shape-error rest expected-length))
       (error error)))))

(defun assemble-piece (blocks expected-length)
  (if (or (not (is_list blocks)) (== 0 (length blocks)))
    (piece-error 'invalid-input "No blocks to assemble")
    (case (first-shape-error blocks expected-length)
      ('none
       (let ((sorted
               (lists:sort
                 (lambda (a b) (< (maps:get 'offset a) (maps:get 'offset b)))
                 blocks)))
         (assemble-loop sorted expected-length 0 '())))
      (error error))))

(defun assemble-loop (blocks expected-length expected-offset acc)
  (case blocks
    ('()
     (if (== expected-offset expected-length)
       (tuple 'ok (iolist_to_binary (lists:reverse acc)))
       (piece-error 'incomplete-piece
                    (lists:flatten
                      (io_lib:format "Blocks cover ~p of ~p bytes"
                                     (list expected-offset expected-length))))))
    ((cons block rest)
     (let ((offset (maps:get 'offset block))
            (data (maps:get 'data block)))
       (if (not (== offset expected-offset))
         (piece-error 'gap-in-blocks
                      (lists:flatten
                        (io_lib:format "Expected offset ~p but got ~p"
                                       (list expected-offset offset))))
         (assemble-loop rest
                        expected-length
                        (+ expected-offset (byte_size data))
                        (cons data acc)))))))

(defun endgame? (piece-state threshold)
  (=< (+ (needed-count piece-state) (in-flight-count piece-state)) threshold))

(defun select-pieces-endgame (piece-state peer-available)
  (let* ((remaining
           (sets:union (maps:get 'needed piece-state)
                       (maps:get 'in-flight piece-state)))
          (selected (sets:intersection (as-set peer-available) remaining)))
    (tuple 'ok (lists:sort (sets:to_list selected)))))
