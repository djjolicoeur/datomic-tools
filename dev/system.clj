(ns system
  (:require [com.stuartsierra.component :as component]
            [datomic-tools.component :as db-component]))

(defn dev-system []
  (component/system-map
   :datomic-uri "datomic:mem://datomic-tools-dev"
   :db (component/using (db-component/new-dev-datomic) [:datomic-uri])))

(def dev-file-helper-config
  {:default [:schema :prod-facts :prod-jobs]
   :migration-schema {:schema [:datomic-tools.migrate/schema-migration
                               "resources/datomic-tools/testmigrations/schema"]
                      :prod-facts [:datomic-tools.migrate/seed-fact-migration
                                   "resources/datomic-tools/testmigrations/facts"]
                      :prod-jobs [:datomic-tools.migrate/job-migration
                                  "resources/datomic-tools/testmigrations/jobs"]}})

(def basic-dev-migration-config
  {:schema "datomic-tools/testmigrations/schema"
   :prod-facts "datomic-tools/testmigrations/facts"
   :prod-jobs "datomic-tools/testmigrations/jobs"})
