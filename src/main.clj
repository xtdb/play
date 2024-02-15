(ns main
  (:require [server]
            [integrant.core :as ig])
  (:gen-class))

(def system
  {:server/server {:join false
                   :port 8000}})

(defn -main [& _args]
  (ig/init system)
  @(delay))
