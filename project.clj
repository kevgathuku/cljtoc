(defproject dev.cljtoc "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "https://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [org.clojure/spec.alpha "0.5.238"]
                 [org.clojure/core.async "1.6.681"]]
  :main ^:skip-aot dev.cljtoc.core
  :target-path "target/%s"
  :plugins [[lein-cloverage "1.2.4"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.3"]
                                  [rewrite-clj/rewrite-clj "1.2.50"]]}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
