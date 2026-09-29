(ns dev.cljtoc.utils
  "Shared byte helpers with no layer dependencies: any layer may require
   this namespace. Equality over raw bytes and byte-array generation for
   specs and tests live here instead of being repeated per namespace."
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]))

(defn bytes-equal?
  "True when two byte arrays hold the same bytes. Thin wrapper over
   java.util.Arrays/equals, shared so every hash comparison spells it
   once."
  [^bytes left ^bytes right]
  (java.util.Arrays/equals left right))

(s/fdef bytes-equal?
  :args (s/cat :left bytes? :right bytes?)
  :ret boolean?)

(defn gen-byte-array
  "Generate a generator for byte arrays of specific length."
  [len]
  (gen/fmap byte-array
            (gen/vector (gen/choose -128 127) len)))
