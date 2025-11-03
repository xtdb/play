(ns xt-play.config)

(def pgwire-port 5432)

(def node-config
  {:server {:port pgwire-port}
   :disk-cache {:path "/tmp/xtdb-cache"}})
