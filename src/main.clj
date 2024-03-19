(ns main
  (:require [server]
            [pgwire :as pgw]
            [integrant.core :as ig])
  (:gen-class))

(def system
  {:server/server {:join false
                   :port 8000}

   ::pgw/playground {}})

(defn -main [& _args]
  (ig/init system)
  @(delay))
