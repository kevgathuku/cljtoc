#!/usr/bin/env bb
;; Advisory lint: every public `defn` in `src/` should carry a co-located
;; `s/fdef`. AGENTS.md requires one for each new public fn, but kondo and the
;; compiler cannot see a missing spec, so this report is the only signal.
;;
;; Non-failing on purpose: the tree still has unspecced public fns. It exits 0
;; always; read the list to keep a new one from joining it.
;;
;; Run: bb scripts/fdef_lint.clj

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn- source-files
  [dir]
  (filter #(str/ends-with? (.getName %) ".clj")
          (file-seq (io/file dir))))

(defn- read-forms
  "All top-level forms in one file, or nil when it does not parse."
  [file]
  (try
    (with-open [reader (java.io.PushbackReader. (io/reader file))]
      (loop [forms []]
        (let [form (read {:eof ::eof :read-cond :allow} reader)]
          (if (= ::eof form)
            forms
            (recur (conj forms form))))))
    (catch Exception _ nil)))

(defn- public-defn?
  [form]
  (and (seq? form)
       (= 'defn (first form))
       (symbol? (second form))
       (not (:private (meta (second form))))
       (not (str/starts-with? (name (second form)) "-"))))

(defn- fdef-target
  [form]
  (when (and (seq? form)
             (contains? #{'s/fdef 'clojure.spec.alpha/fdef} (first form))
             (symbol? (second form)))
    (second form)))

(defn- missing-fdefs
  [file]
  (when-let [forms (read-forms file)]
    (let [specced (into #{} (keep fdef-target) forms)
          public (keep #(when (public-defn? %) (second %)) forms)]
      [file (remove specced public)])))

(defn -main
  [& _]
  (let [rows (map missing-fdefs (source-files "src"))
        unreadable (keep (fn [[file missing]] (when (nil? missing) (str file))) rows)
        offenders (into (sorted-map)
                        (for [[file missing] rows
                              :when (seq missing)]
                          [(str file) (sort missing)]))]
    (doseq [[file names] offenders]
      (println file)
      (doseq [name names]
        (println "  " name)))
    (doseq [file unreadable]
      (println (str file " (unparseable; not scanned)")))
    (println)
    (println (str (reduce + 0 (map (comp count val) offenders))
                  " public fn(s) without a co-located fdef; advisory only."))))

(-main)
