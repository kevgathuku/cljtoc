(ns user
  "REPL welcome namespace. `lein repl` calls (welcome) on startup via
  the dev profile's :repl-options :init — see project.clj. Nothing
  prints at load time, so `lein test` stays quiet.

  Requires nothing project-specific: pull in only the namespaces you
  need, with :reload so edits are picked up."
  (:require [clojure.repl :refer [doc find-doc source]]))

(defn welcome
  "Print the require cheat sheet for interactive probing."
  []
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
  (println "  Spec probes (fully-qualified — this ns has no aliases):")
  (println "    (s/valid? :dev.cljtoc.protocol.tracker/port 70000)")
  (println "    (stest/instrument `dev.cljtoc.protocol.tracker/tracker-error)")
  (println "  Stale session? :reload refreshes only named namespaces —")
  (println "  if a var vanishes that rg proves exists, restart the REPL.")
  (println))
