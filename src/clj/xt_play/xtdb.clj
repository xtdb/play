(ns xt-play.xtdb
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as jdbc-res]
            [xt-play.config :as config]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(defn with-xtdb [f]
  (with-open [node (xtn/start-node config/node-config)]
    (f node)))

(defn query [node q]
  (log/info :query q)
  (xt/q node q (when (string? q) {:key-fn :snake-case-string})))

(defn submit! [node txs opts]
  (log/info :submit-tx txs opts)
  (let [tx (xt/submit-tx node txs opts)
        results (xt/q node '(from :xt/txs [{:xt/id $tx-id} xt/error])
                      {:args {:tx-id (:tx-id tx)}})]
    (log/info :submit-tx-result results)
    (if-let [error (-> results first :xt/error)]
      {:message (ex-message error)
       :exception (.getClass error)
       :data (ex-data error)}
      results)))

(defn with-jdbc [f]
  (with-open [conn (jdbc/get-connection config/db)]
    (f conn)))

(defn jdbc-execute!
  [conn statement]
  (log/info :jdbc-execute! statement)
  (jdbc/execute! conn statement {:builder-fn jdbc-res/as-arrays}))

(comment
  (with-open [node (xtn/start-node config/node-config)]
    (doseq [st [#inst "2022" #inst "2021"]]
      (let [tx (xt/submit-tx node [] {:system-time st})
            results (xt/q node '(from :xt/txs [{:xt/id $tx-id} xt/error])
                          {:basis {:at-tx tx}
                           :args {:tx-id (:tx-id tx)}})]
        (when-let [error (-> results first :xt/error)]
          (throw (ex-info "Transaction error" {:error error})))))))
