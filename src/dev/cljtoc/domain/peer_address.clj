(ns dev.cljtoc.domain.peer-address
  "Peer address value: parsing and formatting of host:port addresses.

   Single owner of the address shape. All seams pass through here
   instead of re-splitting strings at every call site."
  (:require [clojure.string :as str]))

(def default-port 6881)

(defn- parse-port-num
  "Parse a port string to a number in range, or nil."
  [port-str]
  (try
    (let [port-num (Integer/parseInt port-str)]
      (when (<= 0 port-num 65535) port-num))
    (catch NumberFormatException _ nil)))

(defn- with-port
  "Build the result for host with optional port string.
   A missing or empty port falls back to default-port.
   A blank host is an error: it would silently resolve to localhost."
  [host port-str]
  (if (str/blank? host)
    {:error :invalid-address :message (str "blank host in peer address")}
    (if (or (nil? port-str) (empty? port-str))
      {:ok {:host host :port default-port}}
      (if-let [port-num (parse-port-num port-str)]
        {:ok {:host host :port port-num}}
        {:error :invalid-port :message (str "invalid port in peer address: " (pr-str port-str))}))))

(defn host
  "The host part of a parsed address value."
  [address]
  (:host address))

(defn port
  "The port part of a parsed address value."
  [address]
  (:port address))

(defn format-address
  "Format an address value back to its canonical string.
   IPv6 hosts are bracketed: \"[::1]:6881\"."
  [address]
  (let [host (:host address)
        host (if (str/includes? host ":") (str "[" host "]") host)]
    (str host ":" (:port address))))

(defn parse
  "Parse a peer address string into {:ok {:host host :port port}}.
   Accepts \"host:port\", bare \"host\" (default port 6881),
   \"[v6-host]:port\", and bare IPv6 (default port).
   Returns {:error ...} instead of throwing on bad input."
  [address-str]
  (if (or (nil? address-str) (str/blank? address-str))
    {:error :invalid-address :message (str "blank peer address: " (pr-str address-str))}
    (if (str/starts-with? address-str "[")
      (let [[_ host port-str] (re-matches #"\[([^\]]+)\](?::(.*))?" address-str)]
        (if (nil? host)
          {:error :invalid-address :message (str "malformed peer address: " (pr-str address-str))}
          (with-port host port-str)))
      (let [colon-count (count (filter #(= \: %) address-str))]
        (cond
          (zero? colon-count)
          {:ok {:host address-str :port default-port}}

          ;; Bare IPv6: more than one colon outside brackets is
          ;; ambiguous, so the whole string is the host.
          ;; All-colons is not an address at all.
          (> colon-count 1)
          (if (every? #(= \: %) address-str)
            {:error :invalid-address :message (str "blank host in peer address: " (pr-str address-str))}
            {:ok {:host address-str :port default-port}})

          :else
          (let [sep-index (.lastIndexOf address-str ":")]
            (with-port (subs address-str 0 sep-index) (subs address-str (inc sep-index)))))))))
