(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
            [com.stuartsierra.component :as component]
            [reloaded.repl :refer [go init reset reset-all start stop system]]
            [system :as app]))

(set-refresh-dirs "dev/" "src/" "test/")

(reloaded.repl/set-init! app/dev-system)
