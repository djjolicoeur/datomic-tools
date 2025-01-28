(ns datomic-tools.utils
  (:require [com.stuartsierra.component :as component]
            [datomic.peer :as d]
            [com.stuartsierra.component :as component]))

(def ^:dynamic *system*)

(def datomic-endpoint "datomic:mem://datomic-tools")

(defrecord DatomicDB [uri conn]
  component/Lifecycle
  (start [this]
    (if conn this
        (let [uniq (str (java.util.UUID/randomUUID))
              unique-uri (str datomic-endpoint "-" uniq)
              _ (d/create-database unique-uri)
              conn (d/connect-uri unique-uri)]
          (assoc this :uri unique-uri :conn conn))))
  (stop [this]
    (if-not conn this
            (do (d/delete-database uri)
                (assoc this :uri nil :conn nil)))))

(defn new-db []
  (map->DatomicDB {}))

(defn test-system []
  (component/system-map
   :uri datomic-endpoint
   :db (component/using (new-db) [:uri])))

(defn new-isolated-app []
  (let [s (component/start (test-system))]
    (alter-var-root #'*system* (constantly s))
    (println *system*)))

(defn stop-isolated-app []
  (component/stop *system*)
  (alter-var-root #'*system* (constantly nil)))


