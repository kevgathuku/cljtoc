#!/usr/bin/env bb
;; Cross-file symbol rename on the syntax tree, not on text.
;;
;; Replacing symbol text with sed/regex breaks exactly where it hurts:
;; it renames inside strings, comments, and longer names, and it cannot
;; tell `foo/bar` from `bar`. This walks rewrite-clj zippers instead, so
;; only real symbol occurrences change and delimiters stay balanced by
;; construction. (The botched `parse-settings` arity edit that spliced
;; code inside an EDN string literal is the incident this replaces.)
;;
;; Scope is deliberately narrow: exact symbol matches only, no shadowing
;; analysis. A `let`-bound local with the same name gets renamed too —
;; review the diff. Keywords, strings, comments, and namespaced symbols
;; that merely share the name (`other/bar` when renaming `bar`) are left
;; alone: pass the qualified name to rename those.
;;
;; Run: bb scripts/rename_symbol.clj <old> <new> [--write] <files...>
;;   Default is dry-run: prints a unified diff per changed file and exits 1.
;;   --write applies the changes in place and exits 0.
;; (rewrite-clj is built into babashka and a :dev dep for clojure.main,
;; so this never touches prod deps.)

(require '[rewrite-clj.zip :as z]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.java.shell :as shell])

(defn- target-node?
  "True when this zipper location is a plain symbol occurrence of old-name.
   Keywords, strings, comments, and namespaced lookalikes never match:
   only `:token` nodes whose value is exactly the symbol."
  [loc old-name]
  (and (= :token (z/tag loc))
       (let [v (try (z/sexpr loc) (catch Exception _ ::unreadable))]
         (and (symbol? v)
              (= (str v) old-name)))))

(defn- rename-in-source
  "Rewrite every occurrence of old-sym to new-sym in source text.
   Returns [changed? new-text]."
  [source old-sym new-sym]
  (loop [loc (z/of-string source)
         changed? false]
    (cond
      (z/end? loc)
      [changed? (z/root-string loc)]

      (target-node? loc old-sym)
      (recur (z/next (z/replace loc (symbol new-sym))) true)

      :else
      (recur (z/next loc) changed?))))

(defn -main [& args]
  (let [flags (filter #(str/starts-with? % "--") args)
        [old-sym new-sym & files] (remove #(str/starts-with? % "--") args)
        write? (boolean (some #{"--write"} flags))]
    (when (or (nil? old-sym) (nil? new-sym) (empty? files))
      (binding [*out* *err*]
        (println "usage: bb scripts/rename_symbol.clj <old> <new> [--write] <files...>"))
      (System/exit 2))
    (let [changed (atom [])]
      (doseq [f files]
        (let [source (slurp (io/file f))
              [touched? out] (rename-in-source source old-sym new-sym)]
          (when touched?
            (swap! changed conj f)
            (if write?
              (spit (io/file f) out)
              (let [{:keys [out err]} (shell/sh "diff" "-u" f "-"
                                                :in out :dir ".")]
                (print (or out err))
                (flush))))))
      (System/exit (if (and (seq @changed) (not write?)) 1 0)))))

(apply -main *command-line-args*)
