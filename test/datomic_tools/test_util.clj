(ns datomic-tools.test-util
  (:require [com.stuartsierra.component :as component]
            [system :as system]
            [clojure.tools.logging :refer [debug info warn error]]))

(defmacro isnt
  "compliment of clojure.test/is"
  [& body]
  `(clojure.test/is (not ~@body)))

(defn with-system
  "Starts an isolated system, calls f on system, then stops system"
  [system f]
  (let [running-system (component/start-system system)]
    (try
      (f running-system)
      (finally
        (component/stop-system running-system)))))
