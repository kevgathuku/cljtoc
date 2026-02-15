(ns dev.cljtoc.test-utils
  "Shared test utilities and helper functions.")

(defn to-bytes
  "Convert string to UTF-8 byte array."
  [^String s]
  (.getBytes s "UTF-8"))
