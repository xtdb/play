(ns xt-play.config)

(def pgwire-port 5432)

(def db
  {:dbtype "postgresql"
   :dbname "xtdb"
   :user "xtdb"
   :password "xtdb"
   :host "localhost"
   :port pgwire-port})

(def node-config
  {:server
   {:port pgwire-port}})
