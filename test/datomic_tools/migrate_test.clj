(ns datomic-tools.migrate-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [datomic.api :as d]
            [datomic-tools.migrate :as migrate]
            [datomic-tools.test-util :as t]
            [system :as system]))


(def test-path "resources/datomic-tools/testmigrations")

(def test-schema-file "/2023120121011545-user-schema.edn")
(def test-fact-file "/2023120121011545-user-facts.edn")

(def test-schema-dir (str test-path "/schema"))

(def test-schema-path (str test-schema-dir test-schema-file))

;; test schema ids
(def user-schema-id 2023120121011545)
(def company-id 2023121200483614)
(def user-status-id 2023121200490347)

(def test-schema-ids #{user-schema-id company-id user-status-id})

;; test fact ids
(def user-facts-id 2023120121011545)
(def company-facts-id 2023121200483614)

(def test-fact-ids #{user-facts-id company-facts-id})

;; test job ids
(def test-job-id 2023121200490347)

(def test-job-ids #{test-job-id})

(def chunk-schema-id 2024010101010101)
(def chunk-schema-resource
  "datomic-tools/chunk-tests/schema/2024010101010101-chunked-schema.edn")

(def chunk-facts-id 2024010101010202)
(def chunk-facts-resource
  "datomic-tools/chunk-tests/facts/2024010101010202-conflicting-facts.edn")


(defn system->conn
  [system]
  (-> system :db :conn))

(deftest test-installed?
  "Test that migrate/installed? returns false if attributes not present
   and true if all attributes are installed"
  (t/with-system (system/dev-system)
    (fn [system]
      (let [conn (system->conn system)
            before (migrate/installed? conn)
            _ (migrate/ensure-install conn)
            after (migrate/installed? conn)]
        (t/isnt before)
        (is after)))))

(deftest test-ensure-installed
  "Tests migrate/ensure-installed installs all datomic-tools/migrate attributes,
   given an empty DB"
  (t/with-system (system/dev-system)
    (fn [system]
      (let [conn (system->conn system)
            empty-before? (not (migrate/installed? conn))
            db-after (:db-after (migrate/ensure-install conn))
            attr-values (map (partial d/attribute db-after) migrate/migration-attributes)
            attr-count (count attr-values)
            db-attrs (map :ident attr-values)]
        (is empty-before?)
        (t/isnt (empty? attr-values))
        (is (= 6 attr-count))
        (is (= migrate/migration-attributes db-attrs))))))

(deftest test-dir-files
  "Test dir-files returns a map of file names to file resources, given a valid resource
   directory"
  (let [affirmative-test (migrate/dir-files "datomic-tools/testmigrations/schema")
        negative-test (migrate/dir-files "datomic-tools/testmigrations/fake")]
    (is (> (count affirmative-test) 0))
    (is ((set (keys affirmative-test)) test-schema-file))
    (is (= java.net.URI (type (first (affirmative-test test-schema-file)))))
    (is (empty? negative-test))))

(deftest test-get-file-id
  "Tests migrate/get-file-id returns approprite Long value"
  (is (= 2023120121011545 (migrate/get-file-id test-schema-path)))
  (is (thrown? Exception (migrate/get-file-id "resources/path-with-no-ID"))))

(deftest test-is-migration?
  "Tests migration/is-migration? fn"
  (is (migrate/is-migration? test-schema-path))
  (is (migrate/is-migration? "resources/test/job.clj"))
  (t/isnt (migrate/is-migration? "/foo/bar/shouldFail.ts")))

(deftest test-cp-resource->id-map
  "Tests migrate/cp-resource->id-map"
  (let [dir-files (migrate/dir-files "datomic-tools/testmigrations/schema")
        result (->> dir-files (map migrate/cp-resource->id-map))
        keys (into #{} (map (comp first keys) result))
        values (flatten (map vals result))]
    (is (= test-schema-ids keys))
    (is (every? #(= java.net.URI (type %)) values))))


(deftest test-file-map
  "Tests migrate/file-map and migrate/unique-key-merge"
  (let [string-results (migrate/file-map "datomic-tools/testmigrations/schema")
        seq-results (migrate/file-map ["datomic-tools/testmigrations/schema"
                                       "datomic-tools/testmigrations/schema2"])
        dupe-dirs ["datomic-tools/testmigrations/schema"
                   "datomic-tools/testmigrations/schema-dupes"]]
    (is (map? string-results))
    (is (= #{2023120121011545 2023121200490347
             2023121200483614} (set (keys string-results))))
    (is (= #{2023120121011545 2023121200490347
             2023121200483614 2023120121011556} (set (keys seq-results))))
    (is (thrown-with-msg? Exception #"Duplicate keys Found in Migrations"
                          (migrate/file-map dupe-dirs)))
    (is (thrown-with-msg? Exception #"Unsupported type passed to file-map"
                         (migrate/file-map {:test "map"})))))

(deftest test-dbval
  "Tests migrate/dbval returns a valid DB value"
  (t/with-system (system/dev-system)
    (fn [system]
      (is (= datomic.db.Db (type (migrate/dbval (:conn (:db system)))))))))

(deftest test-db-conn
  "Tests that migrate/db-conn creates a valid datomic.peer.LocalCollection"
  (t/with-system (system/dev-system)
    (fn [system]
      (let [conn (migrate/db-conn (:datomic-uri (:db system)))]
        (is (= datomic.peer.LocalConnection (type conn)))))))

(deftest test-type->id-attr
  "Tests helper fn test->id-attr returns the right fields"
  (is (= :datomic-tools.schema-migration/id
         (migrate/type->id-attr migrate/SCHEMA-MIGRATION)))
  (is (= :datomic-tools.fact-migration/id
         (migrate/type->id-attr migrate/SEED-FACT-MIGRATION)))
  (is (= :datomic-tools.job-migration/id
         (migrate/type->id-attr migrate/JOB-MIGRATION))))

(deftest test-type->tx-id-attr
  "Tests helper fn migrate/type->tx-id-attr returns correct atttribute keywords"
  (is (= :datomic-tools.schema-migration/tx-id
         (migrate/type->tx-id-attr migrate/SCHEMA-MIGRATION)))
  (is (= :datomic-tools.fact-migration/tx-id
         (migrate/type->tx-id-attr migrate/SEED-FACT-MIGRATION)))
  (is (= :datomic-tools.job-migration/tx-id
         (migrate/type->tx-id-attr migrate/JOB-MIGRATION))))

(deftest test-existing-migrations-and-partials
  (t/with-system (system/dev-system)
    (fn [system]
      (let [{:keys [schema prod-facts prod-jobs migrate-opts]}
            system/basic-dev-migration-config
            db-conn (:conn (:db system))
            result (migrate/migrate! db-conn schema prod-facts prod-jobs migrate-opts)
            existing-schema (migrate/existing-migrations migrate/SCHEMA-MIGRATION
                                                         db-conn)
            existing-facts (migrate/existing-migrations migrate/SEED-FACT-MIGRATION
                                                        db-conn)
            existing-jobs (migrate/existing-migrations migrate/JOB-MIGRATION
                                                       db-conn)
            ;;test partial helpers
            schema-partial-check (migrate/existing-schema-migrations db-conn)
            fact-partial-check (migrate/existing-fact-migrations db-conn)
            job-partial-check (migrate/existing-job-migrations db-conn)]
        (is (= test-schema-ids existing-schema))
        (is (= test-fact-ids existing-facts))
        (is (= test-job-ids existing-jobs))
        (is (= test-schema-ids schema-partial-check))
        (is (= test-fact-ids fact-partial-check))
        (is (= test-job-ids job-partial-check))))))

(deftest test-migration-available?-and-partials
  (t/with-system (system/dev-system)
    (fn [system]
      (let [{:keys [schema prod-facts prod-jobs migrate-opts]}
            system/basic-dev-migration-config
            db-conn (:conn (:db system))
            _ (migrate/ensure-install db-conn)
            pre-schema (migrate/migration-available? migrate/SCHEMA-MIGRATION
                                                     db-conn
                                                     user-schema-id)
            pre-facts (migrate/migration-available? migrate/SEED-FACT-MIGRATION
                                                    db-conn
                                                    user-facts-id)
            pre-jobs (migrate/migration-available? migrate/JOB-MIGRATION
                                                   db-conn
                                                   test-job-id)
            pre-schema-partial (migrate/schema-available? db-conn user-schema-id)
            pre-facts-partial (migrate/fact-available? db-conn user-facts-id)
            pre-jobs-partial (migrate/job-available? db-conn test-job-id)
            _ (migrate/migrate! db-conn schema prod-facts prod-jobs migrate-opts)
            post-schema (migrate/migration-available? migrate/SCHEMA-MIGRATION
                                                      db-conn
                                                      user-schema-id)
            post-facts (migrate/migration-available? migrate/SEED-FACT-MIGRATION
                                                     db-conn
                                                     user-facts-id)
            post-jobs (migrate/migration-available? migrate/JOB-MIGRATION
                                                    db-conn
                                                    test-job-id)
            post-schema-partial (migrate/schema-available? db-conn user-schema-id)
            post-facts-partial (migrate/fact-available? db-conn user-facts-id)
            post-jobs-partial (migrate/job-available? db-conn test-job-id)]
        (is (true? pre-schema))
        (is (true? pre-facts))
        (is (true? pre-jobs))
        (is (true? pre-schema-partial))
        (is (true? pre-facts-partial))
        (is (true? pre-jobs-partial))
        (is (false? post-schema))
        (is (false? post-facts))
        (is (false? post-jobs))
        (is (false? post-schema-partial))
        (is (false? post-facts-partial))
        (is (false? post-jobs-partial))))))

(deftest test-tx-id
  (t/with-system (system/dev-system)
    (fn [system]
      (let [tx-result (migrate/ensure-install (:conn (:db system)))
            tx-id (migrate/tx-id tx-result)]
        (is (= java.lang.Long (type tx-id)))))))

(deftest test-tx-log
  (t/with-system (system/dev-system)
    (fn [system]
      (let [conn (:conn (:db system))
            tx-result (migrate/ensure-install conn)
            tx-id (migrate/tx-id tx-result)
            data (migrate/tx-log conn tx-id)]
        (println data)
        (println (type (first data)))
        (is (not (empty? data)))
        (is (every? #(= datomic.db.Datum (type %)) data))))))

(deftest test-filter-meta
  (t/with-system (system/dev-system)
    (fn [system]
      (let [conn (:conn (:db system))
            tx-result (migrate/ensure-install conn)
            tx-id (migrate/tx-id tx-result)
            unfiltered (:tx-data tx-result)
            filtered (migrate/filter-meta tx-id (:tx-data tx-result))
            e-ids (into #{} (map :e filtered))
            tx-e-ids (filter #(= tx-id (:e)))]
        (t/isnt (= unfiltered filtered))
        (t/isnt (contains? e-ids tx-id))))))

(deftest chunked-schema-migration-applies-all-chunks
  (t/with-system (system/dev-system)
    (fn [system]
      (let [conn (system->conn system)
            file (io/resource chunk-schema-resource)
            opts {:chunk-size {migrate/SCHEMA-MIGRATION 1}}]
        (migrate/ensure-install conn)
        (migrate/migrate-schema! conn chunk-schema-id file opts)
        (let [db (d/db conn)
              attrs [:chunk-test/alpha :chunk-test/beta :chunk-test/gamma]]
          (doseq [attr attrs]
            (is (d/attribute db attr)))
          (is (false? (migrate/schema-available? conn chunk-schema-id))))))))

(deftest chunked-fact-migration-rolls-back-on-error
  (t/with-system (system/dev-system)
    (fn [system]
      (let [conn (system->conn system)
            {:keys [schema prod-facts prod-jobs migrate-opts]}
            system/basic-dev-migration-config
            file (io/resource chunk-facts-resource)
            opts {:chunk-size {migrate/SEED-FACT-MIGRATION 1}}]
        ;; ensure base schema/data exists for constraints
        (migrate/migrate! conn schema prod-facts prod-jobs migrate-opts)
        (is (thrown? Exception
                     (migrate/migrate-facts! conn chunk-facts-id file opts)))
        (is (true? (migrate/fact-available? conn chunk-facts-id)))
        (let [db (d/db conn)]
          (is (empty?
               (d/q '[:find ?e
                      :in $ ?username
                      :where [?e :user/username ?username]]
                    db "chunked-user-a")))
          (is (empty?
               (d/q '[:find ?e
                      :in $ ?username
                      :where [?e :user/username ?username]]
                    db "chunked-user-b"))))))))

(deftest reverse-datom
  )



