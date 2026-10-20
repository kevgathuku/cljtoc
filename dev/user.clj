(ns user
  "REPL welcome namespace. `lein repl` calls (welcome) on startup via
  the dev profile's :repl-options :init — see project.clj. Nothing
  prints at load time, so `lein test` stays quiet.

  Requires nothing project-specific: pull in only the namespaces you
  need, with :reload so edits are picked up."
  (:require [clojure.repl :refer [doc find-doc source]]
            [clojure.spec.alpha :as s]
            [clojure.tools.namespace.repl :as repl]
            [expound.alpha :as expound]
            [io.aviso.repl :as pretty-repl]))

;; Real vars (not :refer mappings) so both bare use in this namespace
;; and qualified user/refresh use from anywhere else resolve.
(def refresh repl/refresh)
(def refresh-all repl/refresh-all)
(def refresh-dirs repl/refresh-dirs)
(def clear repl/clear)

(defn welcome
  "Print the require cheat sheet for interactive probing."
  []
  (alter-var-root #'s/*explain-out* (constantly expound/printer))
  (pretty-repl/install-pretty-exceptions)
  (println)
  (println "  cljtoc REPL — require what you need, with :reload:")
  (println)
  (println "    (require '[dev.cljtoc.domain.pieces :as pieces] :reload)")
  (println "    (require '[dev.cljtoc.protocol.tracker :as tracker] :reload)")
  (println "    (require '[dev.cljtoc.protocol.peer :as peer] :reload)")
  (println "    (require '[dev.cljtoc.orchestration.download :as download] :reload)")
  (println "    (require '[dev.cljtoc.orchestration.coordinator :as coord] :reload)")
  (println "    (require '[dev.cljtoc.ports.disk :as disk] :reload)")
  (println "    (require '[dev.cljtoc.test-doubles.disk :as mock-disk] :reload)")
  (println "    (require '[clojure.spec.alpha :as s] :reload)")
  (println "    (require '[clojure.spec.test.alpha :as stest] :reload)")
  (println)
  (println "  Reload helpers (clojure.tools.namespace.repl):")
  (println "    (refresh)      ; reload changed namespaces (tracked dirs)")
  (println "    (refresh-all)  ; reload all namespaces, changed or not")
  (println "    (refresh-dirs) ; print directories being watched")
  (println "    (clear)        ; clear refresh tracker (after fixing compile errors)")
  (println "  Same protocol caveat as :reload-all: a refresh redefines")
  (println "  defrecords, so protocol instances from before the refresh")
  (println "  stop satisfying the new protocol — restart if workers act stale.")
  (println)
  (println "  Spec probes (fully-qualified — this ns has no aliases):")
  (println "    (s/valid? :dev.cljtoc.protocol.tracker/port 70000)")
  (println "    (stest/instrument `dev.cljtoc.protocol.tracker/tracker-error)")
  (println "  Readable errors (wired above on startup):")
  (println "    spec failures print through expound automatically")
  (println "    (pretty-repl/pretty-pst *e) ; demunged stacktrace of last error")
  (println "  Private fns (specs are the interface; helpers stay defn-):")
  (println "    (@#'tracker/valid-ipv4? \"10.0.0.9\") ; deref the var, then call")
  (println "  Stale session? :reload refreshes only named namespaces —")
  (println "  if a var vanishes that rg proves exists, restart the REPL.")
  (println))
