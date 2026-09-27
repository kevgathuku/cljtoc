(ns dev.cljtoc.test-utils
  "Shared test utilities and helper functions."
  (:require [clojure.spec.test.alpha :as stest]))

(defn to-bytes
  "Convert string to UTF-8 byte array."
  [^String s]
  (.getBytes s "UTF-8"))

(defn check-fdefs
  "Run stest/check over syms, each naming a var with an fdef.
   Returns {sym failure} for failures only, so one deftest can pin a whole
   namespace's check-grade specs while still naming the culprit and its
   shrunk counterexample."
  [syms num-tests]
  (into {}
        (comp (map #(first (stest/check [%] {:clojure.spec.test.check/opts
                                             {:num-tests num-tests}})))
              (keep (fn [{:keys [sym failure]}]
                      (when failure [sym failure]))))
        syms))
