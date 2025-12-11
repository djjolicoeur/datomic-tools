(defproject djjolicoeur/datomic-tools "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/tools.logging "1.2.4"]
                 [com.datomic/peer "1.0.7021"]
                 [metosin/malli "0.13.0"]
                 [com.stuartsierra/component "1.1.0"]
                 [cpath-clj "0.1.2"]
                 [clj-time "0.15.2"]
                 [reloaded.repl "0.2.4"]
                 [clj-log4j2 "0.4.0"]
                 [logback-bundle/core-bundle "0.3.0"]]
  :jvm-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"]
  :plugins [[cider/cider-nrepl "0.43.1"]]
  :repl-options {:init-ns user}
  :profiles {:dev {:source-paths ["dev"]}}
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/CLOJARS_USERNAME
                                    :password :env/CLOJARS_PASSWORD
                                    :sign-releases false}]])
