(ns system
  (:require [com.stuartsierra.component :as component]
            [datomic-tools.component :as db-component]))

(def default-migrate-opts
  {:chunk-size {:schema 500
                :facts 1000
                :jobs 250}})

(def env->migrate-opts
  {:dev default-migrate-opts
   :test {:chunk-size {:schema 250
                       :facts 500
                       :jobs 125}}
   :prod {:chunk-size nil}})

(defn migrate-opts-for
  "Lookup chunk sizes for a given environment keyword.
   Falls back to default-migrate-opts when env is unknown."
  [env]
  (get env->migrate-opts env default-migrate-opts))

(defn migration-config
  "Returns the base migration config map for a given environment keyword."
  ([]
   (migration-config :dev))
  ([env]
   {:schema "datomic-tools/testmigrations/schema"
    :prod-facts "datomic-tools/testmigrations/facts"
    :prod-jobs "datomic-tools/testmigrations/jobs"
    :migrate-opts (migrate-opts-for env)}))

(def basic-dev-migration-config
  (migration-config :dev))

(def dev-file-helper-config
  {:default [:schema :prod-facts :prod-jobs]
   :migration-schema {:schema [:datomic-tools.migrate/schema-migration
                               "resources/datomic-tools/testmigrations/schema"]
                      :prod-facts [:datomic-tools.migrate/seed-fact-migration
                                   "resources/datomic-tools/testmigrations/facts"]
                      :prod-jobs [:datomic-tools.migrate/job-migration
                                  "resources/datomic-tools/testmigrations/jobs"]}})

(defn dev-system []
  (component/system-map
   :datomic-uri "datomic:mem://datomic-tools-dev"
   :db (component/using (db-component/new-dev-datomic) [:datomic-uri])))
