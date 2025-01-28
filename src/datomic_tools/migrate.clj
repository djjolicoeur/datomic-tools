(ns datomic-tools.migrate
  (:require [datomic.api :as d]
            [clojure.string :as s]
            [clojure.set :as st]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [debug info warn error]]
            [cpath-clj.core :as cp]
            [clj-time.core :as t]
            [clj-time.format :as ft]))

;; Definitions

;; Migration functions

(def schema-resource "migrations.edn")

(def SCHEMA-MIGRATION :datomic-tools.migration/schema-migration)

(def SEED-FACT-MIGRATION :datomic-tools.migration/seed-fact-migration)

(def JOB-MIGRATION :datomic-tools.migration/job-migration)

(def migration-entity-types
  [SCHEMA-MIGRATION
   SEED-FACT-MIGRATION
   JOB-MIGRATION])

(def migration-attributes
  [:datomic-tools.schema-migration/id
   :datomic-tools.schema-migration/tx-id
   :datomic-tools.fact-migration/id
   :datomic-tools.fact-migration/tx-id
   :datomic-tools.job-migration/id
   :datomic-tools.job-migration/tx-id])

(def type->id-attrs
  {SCHEMA-MIGRATION {:id :datomic-tools.schema-migration/id
                     :tx-id :datomic-tools.schema-migration/tx-id}
   SEED-FACT-MIGRATION {:id :datomic-tools.fact-migration/id
                        :tx-id :datomic-tools.fact-migration/tx-id}
   JOB-MIGRATION {:id :datomic-tools.job-migration/id
                  :tx-id :datomic-tools.job-migration/tx-id}})

(defn installed?
  "Given a DB connection, returns true if all migration-attributes are installed,
   otherwise false"
  [db-conn]
  (let [dbval (d/db db-conn)]
    (->> (map (partial d/attribute dbval) migration-attributes)
         (filter nil?)
         count
         (= 0))))

(defn ensure-install
  "Checks that all DB attributes are installed. Transacts all attributes in 
   schema-resource if not and returns the TX result"
  [db-conn]
  (when-not (installed? db-conn)
    (info :msg "Installing datomic-tools.migration schema")
    (let [schema (-> schema-resource
                     io/resource
                     io/reader
                     datomic.Util/readAll)]
      @(d/transact db-conn schema))))


;; file helpers

(defn dir-files
  "Given a resource direcrtory name, outputs a map of file/resource paths
   and the resource associated with that path. If dir is nil, returns an
   empty map"
  [dir]
  (let [resource (io/resource dir)]
    (if resource
      (cp/resources resource)
      (do (warn :msg (format "No resource found at: %s, continuing without" dir))
          {}))))

(defn get-file-id
  "Given a file URI string, return the Migration ID of the file, 
   e.g. if file is 1234-user-schema.edn, return Long 1234"
  [path]
  (-> path
      (s/split #"/")
      last
      (s/split #"[-_\.]")
      first
      java.lang.Long/parseLong))

(defn is-migration?
  "Given a filename or path, returns true if the file extension is 
   either .edn or .clj, false otherwise"
  [filename]
  (#{"edn" "clj"} (last (s/split filename #"\."))))

(defn cp-resource->id-map
  "Given a nested vector of [[filepath (k) fileinfo (v),
   Returns a map of migration-id -> URI"
  [[k v]]
  (when (is-migration? k)
    {(get-file-id k) (first v)}))

(defn unique-key-merge
  [m1 m2]
  (let [keyz (map (comp set keys) [m1 m2])
        dupes (apply clojure.set/intersection keyz)]
    (if-not (seq dupes)
      (merge m1 m2)
      (throw (ex-info "Duplicate keys Found in Migrations"
                      {:duplicates dupes})))))

(defn file-map
  [dir]
  (cond
    (string? dir) (->> (dir-files dir)
                       (map cp-resource->id-map)
                       (filter identity)
                       (into {}))
    (sequential? dir) (->> dir
                           (map file-map)
                           (reduce unique-key-merge {}))
    :else (throw
           (ex-info "Unsupported type passed to file-map"
                    {:causes #{:unsupported-type}
                     :type (type dir)}))))

(defn dbval
  [db-conn]
  (d/db db-conn))

(defn db-conn
  [db-uri]
  (d/connect db-uri))

(defn type->id-attr
  {:pre [(keyword? type)]}
  [type]
  (->> type->id-attrs type :id))

(defn type->tx-id-attr
  {:pre [(keyword? type)]}
  [type]
  (->> type->id-attrs type :tx-id))

(defn existing-migrations
  [type db-conn]
  (->> type
       type->id-attr
       (d/q '[:find ?id
              :in $ ?id-attr
              :where [_ ?id-attr ?id]]
            (dbval db-conn))
       (reduce concat)
       set))

(def existing-schema-migrations
  (partial existing-migrations SCHEMA-MIGRATION))

(def existing-fact-migrations
  (partial existing-migrations SEED-FACT-MIGRATION))

(def existing-job-migrations
  (partial existing-migrations JOB-MIGRATION))

(defn migration-available?
  [type db-conn id]
  (->> type
       type->id-attr
       (d/q '[:find ?e
              :in $ ?id ?id-attr
              :where [?e ?id-attr ?id]]
            (dbval db-conn) id)
       empty?))

(def schema-available?
  (partial migration-available? SCHEMA-MIGRATION))

(def fact-available?
  (partial migration-available? SEED-FACT-MIGRATION))

(def job-available?
  (partial migration-available? JOB-MIGRATION))

(defn tx-id
  [tx-result]
  (->> (:tx-data tx-result) (map :tx) first))

(defn tx-log
  [conn tx-id]
  (let [log (d/log conn)]
    (when log
      (->> (d/tx-range log tx-id nil) first :data))))

(defn filter-meta
  [tx-id datoms]
  (filter #(not (= tx-id (:e %))) datoms))

(defn reverse-datom
  [datom]
  (if (:added datom)
    [:db/retract (:e datom) (:a datom) (:v datom)]
    [:db/add (:e datom) (:a datom) (:v datom)]))

(defn reverse-datoms
  [datoms]
  (mapv reverse-datom datoms))

(defn rollback-tx
  [conn tx-id]
  (let [data (tx-log conn tx-id)]
    (when data
      @(->> data
            (filter-meta tx-id)
            reverse-datoms
            (d/transact conn)))))


;; Schema migrations
(defn transact-file
  [db-conn type id migration-eid data]
  (info :msg (format "Migrating: %s - %s" type id))
  (debug :msg "Transacting Data:" :data data)
  (try
    (let [id-attr (type->id-attr type)
          tx-id-attr
          (type->tx-id-attr type)
          tx-res @(d/transact db-conn data)
          tx-id (tx-id tx-res)]
      @(d/transact db-conn [[:db/add migration-eid tx-id-attr tx-id]]))
    (catch Exception e
      (error :msg (format "Failed schema migration: %s" id) :data (:ex-data e))
      @(d/transact db-conn [[:db.fn/retractEntity migration-eid]])
      (throw e))))

(defn edn-available?
  [type db-conn id]
  (condp = type
        SCHEMA-MIGRATION (schema-available? db-conn id)
        SEED-FACT-MIGRATION (fact-available? db-conn id)))

(defn migrate-edn-file!
  [type db-conn id file]
  (if (edn-available? type db-conn id)
    (let [id-attr (type->id-attr type)
          data (datomic.Util/readAll (io/reader file))
          temp (d/tempid :db.part/user)
          {:keys [db-after tempids]} @(d/transact db-conn [{:db/id temp id-attr id}])
          migration-eid (d/resolve-tempid db-after tempids temp)]
      (transact-file db-conn type id migration-eid data))
    (info :msg (format "%s Migration %s handled elsewhere, already exists in db" type id))))

(defn migrate-file-map
  [type db-conn migration-path]
  (let [migration-files (file-map migration-path)
        file-migrations (set (keys migration-files))
        db-migrations (existing-schema-migrations db-conn)
        to-migrate (->> (clojure.set/difference file-migrations db-migrations)
                        (sort <))]
    (->> to-migrate
         (select-keys migration-files)
         (map (fn [[k v]] {k {:type type :file v}}))
         (into {}))))

(def migrate-schema! (partial migrate-edn-file! SCHEMA-MIGRATION))

(def migrate-schema-map (partial migrate-file-map SCHEMA-MIGRATION))


;; Fact migrations

(def migrate-facts! (partial migrate-edn-file! SEED-FACT-MIGRATION))

(def migrate-facts-map (partial migrate-file-map SEED-FACT-MIGRATION))

;; Job migrations -- gets a 'lil hacky here lol

(declare ^:dynamic *generate-tx*)

(defn bind-tx-fn!
  [f]
  (alter-var-root #'*generate-tx* (constantly f)))

(def migrate-job-map (partial migrate-file-map JOB-MIGRATION))

(defn transact-job
  [db-conn id job-eid data]
  (info :msg (format "Migrating Job: %s" id))
  (try
    (let [tx-res @(d/transact db-conn data)
          tx-id (tx-id tx-res)
          tx-id-attr (type->tx-id-attr JOB-MIGRATION)]
      @(d/transact db-conn [[:db/add job-eid tx-id-attr tx-id]]))
    (catch Exception e
      (error :msg "failed to migrate job:" :job-id id :data (ex-data e))
      @(d/transact db-conn [[:db.fn/retractEntity job-eid]])
      (throw e))
    (finally
      (info :msg "Releasing job TX" :job-id id)
      (alter-var-root #'*generate-tx* (constantly nil)))))

(defn migrate-job!
  [db-conn id file]
  (if (job-available? db-conn id)
    (let [_ (load-reader (io/reader file))
          data (*generate-tx* (d/db db-conn))
          temp (d/tempid :db.part/user)
          id-attr (type->id-attr JOB-MIGRATION)
          {:keys [db-after tempids]} @(d/transact db-conn
                                                  [{:db/id temp id-attr id}])
          job-eid (d/resolve-tempid db-after tempids temp)]
      (transact-job db-conn id job-eid data))
    (info :msg "Job migration handled elsewhere" :job-id id)))

;; Top level migration fns

(defn merge-fn
  "Merge function for merge-with with variable number of maps 
   with singleton values into a vector"
  [a b]
  (if (vector? a) (conj a b) [a b]))

(defn typed-file
  "given a list of files, output one of :type"
  [type files]
  (first (filter #(= (:type %) type) files)))

(defn order-files
  "Given a list of migration maps associated with a migration ID, 
   output a list of ordered files"
  [files]
  ((apply juxt (map #(partial typed-file %) migration-entity-types)) files))


(defmulti migrate-file!
  (fn [db-conn id file-info] (:type file-info)))

(defmethod migrate-file! SCHEMA-MIGRATION
  [db-conn id file-info]
  (migrate-schema! db-conn id (:file file-info)))

(defmethod migrate-file! SEED-FACT-MIGRATION
  [db-conn id file-info]
  (migrate-facts! db-conn id (:file file-info)))

(defmethod migrate-file! JOB-MIGRATION
  [db-conn id file-info]
  (migrate-job! db-conn id (:file file-info)))

(defn migrate!
  [db-conn schema-path facts-path job-path]
  (ensure-install db-conn)
  (let [schema-migrations (migrate-schema-map db-conn schema-path)
        fact-migrations (migrate-facts-map db-conn facts-path)
        job-migrations (migrate-job-map db-conn job-path)
        file-map (->> (merge-with merge-fn schema-migrations fact-migrations job-migrations)
                        (into (sorted-map-by <)))]
    (doseq [[k v] file-map]
      (if (vector? v)
        (let [[schema-file fact-file job-file] (order-files v)]
          (info :msg "Migrating files" :id k :files [schema-file fact-file job-file])
          (when schema-file
            (migrate-file! db-conn k schema-file))
          (when fact-file
            (migrate-file! db-conn k fact-file))
          (when job-file
            (migrate-file! db-conn k job-file)))
        (migrate-file! db-conn k v)))))

;; File Creation Helpers

(def timestamp-format (ft/formatter "yyyyMMddHHmmssSS"))

(defn generate-timestamp
  []
  (ft/unparse timestamp-format (t/now)))

(defmulti filename (fn [timestamp label [k [type path]]]))

(defmethod filename JOB-MIGRATION
  [timestamp label [k [type path]]]
  [type (format "%s/%s-%s-%s.clj" path timestamp label (name k))])

(defmethod filename :default
  [timestamp label [k [type path]]]
  [type (format "%s/%s-%s-%s.edn" path timestamp label (name k))])

(defn filter-paths
  [config keys]
  (if (seq keys)
    (select-keys config keys)
    config))

(defmulti generate-file (fn [type & args] type))

(defmethod generate-file :job [type file]
  (let [name (last (s/split file #"/"))]
    (spit file
          (str (format ";; %s --- Generated by datomic-tools\n" name)
               "(ns <REPLACE ME>\n\t"
               "(:require [datomic-tools.migrate :as m]"
               "\n\t\t[datomic.api :as d]))"))))

(defmethod generate-file :default [type file]
  (spit file
        (format ";; %s --- Generated by datomic-tools\n" (last (s/split file #"/")))))

(defn generate-filenames
  [config label keys]
  (let [paths (filter-paths (:migration-schema config) (or keys (:default config)))
        ts (generate-timestamp)]
    (loop [file-paths (seq paths) out []]
      (if-let [path (first file-paths)]
        (recur (rest file-paths)
               (conj out (filename ts label path)))
        out))))

(defn generate-migration-files!
  "Creates file stubs on existing paths for migration, e.g. 

  (def config {:default [:schema :prod-jobs :prod-facts]
               :migration-schema
               {:schema [:datomic-tools.migrate/schema-migration \"resources/datomic-tools/migrations/schema]\"
                :dev-facts [:datomic-tools.migrate/seed-fact-migration \"resources/datomic-tools/migrations/dev-facts\"]
                :prod-facts [:datomic-tools.migrate/seed-fact-migration \"resources/datomic-tools/migrations/prod-facts\"]
                :dev-jobs [:datomic-tools.migrate/job-migration \"resources/datomic-tools/migrations/dev-jobs\"]
                :prod-jobs [:datomic-tools.migrate/job \"resources/datomic-tools/migratinos/prod-jobs\"]}})
  (generate-migration-files! config \"test\")
  would create:
  resources/datomic-tools/migrations/schema/2023112717052224-test-schema.edn
  resources/datomic-tools/migrations/prod-jobs/2023112717052224-test-prod-jobs.edn
  resources/datomic-tools/migrations/prod-facts/2023112717052224-test-prod-facts.edn

  to restrict which paths to generate files for, add the config keys you wish to
    create files for, e.g.

  (generate-migration-files! config \"test\" :schema)
  => resources/datomic-tools/migrations/schema/2023112717121251-test-schema.edn
  and
  (generate-migration-files! config \"test\" :schema :prod-facts)

  creates both

  resources/datomic-tools/migrations/schema/2023112717140463-test-schema.edn
  resources/datomic-tools/migrations/prod-facts/2023112717140463-test-prod-facts.edn"
  [config label & keys]
  (let [file-names (generate-filenames config label keys)]
    (doseq [[type file] file-names]
      (debug "Generating migration file:" file)
      (generate-file type file))
    file-names))





