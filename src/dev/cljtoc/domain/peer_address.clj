(ns dev.cljtoc.domain.peer-address
  "Peer address value: parsing and formatting of host:port addresses.

   Single owner of the address shape. All seams pass through here
   instead of re-splitting strings at every call site."
  (:require [clojure.string :as str]))

(def default-port 6881)

(defn- parse-port-num
  "Parse a port string to a number in range, or nil."
  [s]
  (try
    (let [p (Integer/parseInt s)]
      (when (<= 0 p 65535) p))
    (catch NumberFormatException _ nil)))

(defn- with-port
  "Build the result for host h with optional port string p.
   A missing or empty port falls back to default-port.
   A blank host is an error: it would silently resolve to localhost."
  [h p]
  (if (str/blank? h)
    {:error :invalid-address :message (str "blank host in peer address")}
    (if (or (nil? p) (empty? p))
      {:ok {:host h :port default-port}}
      (if-let [port (parse-port-num p)]
        {:ok {:host h :port port}}
        {:error :invalid-port :message (str "invalid port in peer address: " (pr-str p))}))))

(defn host
  "The host part of a parsed address value."
  [addr]
  (:host addr))

(defn port
  "The port part of a parsed address value."
  [addr]
  (:port addr))

(defn format-address
  "Format an address value back to its canonical string.
   IPv6 hosts are bracketed: \"[::1]:6881\"."
  [addr]
  (let [h (:host addr)
        h (if (str/includes? h ":") (str "[" h "]") h)]
    (str h ":" (:port addr))))

(defn parse
  "Parse a peer address string into {:ok {:host h :port p}}.
   Accepts \"host:port\", bare \"host\" (default port 6881),
   \"[v6-host]:port\", and bare IPv6 (default port).
   Returns {:error ...} instead of throwing on bad input."
  [s]
  (if (or (nil? s) (str/blank? s))
    {:error :invalid-address :message (str "blank peer address: " (pr-str s))}
    (if (str/starts-with? s "[")
      (let [[_ h p] (re-matches #"\[([^\]]+)\](?::(.*))?" s)]
        (if (nil? h)
          {:error :invalid-address :message (str "malformed peer address: " (pr-str s))}
          (with-port h p)))
      (let [colon-count (count (filter #(= \: %) s))]
        (cond
          (zero? colon-count)
          {:ok {:host s :port default-port}}

          ;; Bare IPv6: more than one colon outside brackets is
          ;; ambiguous, so the whole string is the host.
          ;; All-colons is not an address at all.
          (> colon-count 1)
          (if (every? #(= \: %) s)
            {:error :invalid-address :message (str "blank host in peer address: " (pr-str s))}
            {:ok {:host s :port default-port}})

          :else
          (let [i (.lastIndexOf s ":")]
            (with-port (subs s 0 i) (subs s (inc i)))))))))
