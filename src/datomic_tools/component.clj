(ns datomic-tools.component
  (:require [com.stuartsierra.component :as component]
            [clojure.walk :refer [postwalk]]
            [datomic.peer :as d]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [debug info warn error]]))

(defn- use-method
  [^clojure.lang.MultiFn multifn dispatch-val func]
  (. multifn addMethod dispatch-val func))


;;-------------------------------------------------
;; DB Boostrapping and setup functions
;;-------------------------------------------------
;;


(defrecord DatomicDev [datomic-uri conn]
  component/Lifecycle
  (start [this]
    (info "Starting Datomic")
    (if conn
      this
      (let [datomic-uri (str datomic-uri "-" (java.util.UUID/randomUUID))
            db (d/create-database datomic-uri)
            conn (d/connect-uri datomic-uri)]
        (assoc this :conn conn :datomic-uri datomic-uri))))
  (stop [this]
    (if conn
      (do
        (info "Stopping Datomic")
        (info (format "Deleting Datomic db: %s" datomic-uri))
        (d/delete-database datomic-uri))
      this))
  clojure.lang.IDeref
  (deref [this] (d/db conn)))

(defmethod print-method DatomicDev [v ^java.io.Writer w]
  (let [{datomic-uri :datomic-uri conn :conn} v]
      (.write w (str "#<DatomicDev"
                     {:datomic-uri datomic-uri :conn conn} ">"))))

(defmethod print-dup DatomicDev [v w]
  (print-method v w))

(use-method clojure.pprint/simple-dispatch DatomicDev pr)


(defn new-dev-datomic
  ([]
   (map->DatomicDev {}))
  ([datomic-uri]
   (map->DatomicDev {:datomic-uri datomic-uri})))

(defrecord DatomicConnection [datomic-uri conn]
  component/Lifecycle
  (start [this]
    (info "Starting Datomic...")
    (if conn
      this
      (let [db (d/create-database datomic-uri)
            conn (d/connect-uri datomic-uri)]
         (assoc this :conn conn :datomic-uri datomic-uri))))
  (stop [this]
    (if-not conn
      this
      (assoc this :conn nil)))
  clojure.lang.IDeref
  (deref [this] (d/db conn)))

(defn new-datomic-conn
  ([]
   (map->DatomicConnection {}))
  ([datomic-uri]
   (map->DatomicConnection {:datomic-uri datomic-uri})))

(defmethod print-method DatomicConnection [v ^java.io.Writer w]
  (let [{datomic-uri :datomic-uri} v]
    (.write w (str "#<DatomicConnection:" {:datomic-uri datomic-uri}))))

(defmethod print-dup DatomicConnection [v ^java.io.Writer w]
  (print-method v w))

(use-method clojure.pprint/simple-dispatch DatomicConnection pr)






