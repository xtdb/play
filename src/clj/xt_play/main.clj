(ns xt-play.main
  (:require [xt-play.server :as server]
            [xt-play.handler :as handler]
            [integrant.core :as ig])
  (:gen-class))

(def system
  {::server/server {:join false
                    :port 8000
                    :handler (ig/ref ::handler/handler)}
   ::handler/handler {}})

(defn -main [& _args]
  (ig/init system)
  @(delay))
