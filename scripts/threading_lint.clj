;; Structural lint: no `->` / `->>` threading form inside a `#()` reader fn
;; may feed an interop call.
;;
;; A `^Type` hint on a `->` chain evaporates during macroexpansion, so an
;; interop call over the chain reflects even when hinted — the compiler
;; warning points at the call, never at the hint, which sends you hunting
;; in the wrong place. Nested keyword calls carry hints fine. Keep new
;; fdef `:fn` bodies and inline checks on the keyword-call shape where
;; interop is downstream; this lint fails otherwise. (Plain `->` inside
;; `#()` over pure functions — `=`, `count`, `s/valid?` — is fine and
;; stays.)
;;
;; Run: lein run -m clojure.main scripts/threading_lint.clj
;; (rewrite-clj lives in the :dev profile, so this never touches prod deps.)

(require '[rewrite-clj.zip :as z]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn- thread-node?
  "True when loc is a `(-> ...)` or `(->> ...)` list."
  [loc]
  (boolean
   (and (z/list? loc)
        (let [head (z/down loc)]
          (and head (contains? #{"->" "->>"} (z/string head)))))))

(defn- interop-head?
  "True when a call head needs compiler type info: dotted methods/fields,
   array intrinsics, `new`, or `Class/static` calls (uppercase host before
   the slash, so namespace-qualified functions like `s/valid?` stay out)."
  [head-str]
  (boolean
   (or (str/starts-with? head-str ".")
       (contains? #{"alength" "aget" "aset" "new"} head-str)
       (re-find #"^[A-Z].*/" head-str))))

(defn- inside-reader-fn?
  "True when loc sits under a `#()` node (`:fn` tag in rewrite-clj)."
  [loc]
  (loop [level (z/up loc)]
    (cond
      (nil? level) false
      (= :fn (z/tag level)) true
      :else (recur (z/up level)))))

(defn- feeds-interop?
  "True when the `->` node is a direct argument of an interop call."
  [loc]
  (let [parent (z/up loc)]
    (boolean
     (and parent
          (z/list? parent)
          (let [head (z/down parent)]
            (and head (interop-head? (z/string head))))))))

(defn- scan-string
  "All [row col] hits of interop-fed threading inside `#()` in one source."
  [source]
  (let [root (z/of-string (str "[" source "]") {:track-position? true})]
    (loop [loc root hits []]
      (let [next-hit (z/find-next loc z/next thread-node?)]
        (if (nil? next-hit)
          hits
          (recur next-hit
                 (if (and (inside-reader-fn? next-hit)
                          (feeds-interop? next-hit))
                   (conj hits (z/position next-hit))
                   hits)))))))

;; Self-test: the query fires on the banned shape and stays quiet on the
;; blessed ones. A lint that cannot demonstrate itself is decoration.
(assert (= 1 (count (scan-string "#(= (alength (-> % :a :b)))"))))
(assert (= 0 (count (scan-string "#(= (alength ^bytes (:b (:a %))))"))))
(assert (= 0 (count (scan-string "#(if (s/valid? (-> % :a) (-> % :b)) 1 2)"))))
(assert (= 0 (count (scan-string "(defn f [m] (let [b ^bytes (-> m :a :b)] (alength b)))"))))
(println "threading-lint self-test passed")

(let [files (->> (io/file "src")
                 file-seq
                 (filter #(.isFile %))
                 (filter #(str/ends-with? (str %) ".clj"))
                 (sort-by str))
      hits (into []
                 (comp (map (fn [file]
                              [file (scan-string (slurp file))]))
                       (filter (comp seq second)))
                 files)]
  (doseq [[file positions] hits]
    (doseq [[row col] positions]
      (println (str file ":" row ":" col ": threading form feeding interop inside #()"))))
  (if (seq hits)
    (do (println (str (count hits) " file(s) with threading feeding interop inside #()"))
        (System/exit 1))
    (println (str "threading-lint clean across " (count files) " files"))))
