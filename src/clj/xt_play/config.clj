(ns xt-play.config)

(def pgwire-port 5432)

(def node-config
  {:server {:port pgwire-port}
   :disk-cache {:path "/tmp/xtdb-cache"
                :max-size-bytes (* 128 1024 1024)} ; 128MB - limit for Lambda
   :compactor {:threads 0}})
