(ns xt-play.server
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(defn start
  [{:keys [join port handler] :or {port 8000}}]
  ; NOTE: This ensure xtdb is warmed up before starting the server
  ;       Otherwise, the first few requests will fail
  (with-open [node (xtn/start-node {})]
    (xt/status node))
  (let [server (jetty/run-jetty handler
                                {:port port, :join? join})]
    (log/info "server running on port" port)
    server))

(defmethod ig/init-key ::server [_ opts]
  (start opts))

(defmethod ig/halt-key! ::server [_ server]
  (.stop server))
