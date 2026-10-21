;;;; Resume state codec: encode a download record to bytes and back.
;;;; Ported from Elixir TorrentClient.Disk.encode_resume/decode_resume.
;;;;
;;;; Erlang carries binaries natively in term_to_binary, so no tagged-hex
;;;; indirection is needed (unlike the Clojure EDN seam).

(defmodule resume
  (export
   (decode 1)
   (encode 1)))

(defun resume-version () 1)

(defun valid-info-hash? (hash)
  (and (is_binary hash) (> (byte_size hash) 0)))

(defun valid-positive? (n)
  (and (is_integer n) (> n 0)))

(defun resume-indices (verified)
  (cond
    ((is_list verified)
     (if (lists:all (lambda (i) (and (is_integer i) (>= i 0))) verified)
       (tuple 'ok (lists:usort verified))
       (tuple 'error 'invalid-verified)))
    ((is_tuple verified) ; sets are tuples in LFE? no, sets are opaque
     (tuple 'error 'invalid-verified))
    ('true
     (tuple 'error 'invalid-verified))))

(defun encode (state)
  (case (maps:get 'info-hash state 'undefined)
    ('undefined
     (tuple 'error 'invalid-resume))
    (hash
     (if (valid-info-hash? hash)
       (let ((total (maps:get 'total state 'undefined))
              (plen (maps:get 'piece-length state 'undefined))
              (verified (maps:get 'verified state 'undefined)))
         (if (and (valid-positive? total) (valid-positive? plen))
           (case (resume-indices verified)
             ((tuple 'ok indices)
              (tuple 'ok
                     (erlang:term_to_binary
                       (map 'v (resume-version)
                            'info-hash hash
                            'verified indices
                            'total total
                            'piece-length plen))))
             ((tuple 'error reason)
              (tuple 'error reason)))
           (tuple 'error 'invalid-resume)))
       (tuple 'error 'invalid-resume)))))

(defun safe-binary-to-term (binary)
  (try
    (tuple 'ok (erlang:binary_to_term binary (list 'safe)))
    (catch
      ((tuple _ _ _)
       (tuple 'error 'corrupt-resume)))))

(defun unhex (hex)
  (if (and (is_binary hex) (== 20 (byte_size hex)))
    (tuple 'ok hex)
    (tuple 'error 'invalid-info-hash)))

(defun decode-payload (term)
  (case term
    ((map 'v
          1
          'info-hash
          hash
          'verified
          verified
          'total
          total
          'piece-length
          plen)
     (if (and (valid-positive? total) (valid-positive? plen))
       (case (unhex hash)
         ((tuple 'ok h)
          (case (resume-indices verified)
            ((tuple 'ok indices)
             (tuple 'ok
                    (map 'info-hash h
                         'verified indices
                         'total total
                         'piece-length plen)))
            ((tuple 'error reason)
             (tuple 'error reason))))
         ((tuple 'error reason)
          (tuple 'error reason)))
       (tuple 'error 'invalid-resume)))
    (_
     (tuple 'error 'invalid-resume))))

(defun decode (binary)
  (if (is_binary binary)
    (case (safe-binary-to-term binary)
      ((tuple 'ok term)
       (decode-payload term))
      ((tuple 'error reason)
       (tuple 'error reason)))
    (tuple 'error 'invalid-resume)))
