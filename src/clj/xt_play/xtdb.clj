(ns xt-play.xtdb
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [xt-play.config :as config]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [xtdb.next.jdbc :as xjdbc]))

(defn with-xtdb [f]
  (with-open [node (xtn/start-node config/node-config)]
    (f node)))

(defn query [node q]
  (log/debug :query q)
  (xt/q node q (when (string? q) {:key-fn :snake-case-string})))

(defn submit! [node txs opts]
  (log/debug :submit-tx txs opts)
  (let [tx (xt/submit-tx node txs opts)
        results (xt/q node '(from :xt/txs [{:xt/id $tx-id} xt/error])
                      {:args {:tx-id (:tx-id tx)}})]
    (log/debug :submit-tx-result results)
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
  (log/debug :jdbc-execute! statement)
  (let [ps (jdbc/prepare conn statement)
        result (jdbc/execute! ps {:builder-fn xjdbc/builder-fn})
        warnings (.getWarnings ps)
        w-msgs (atom [])]
    (loop [w warnings]
      (when w
        (swap! w-msgs conj (ex-message w))
        (recur (.getNextWarning w))))
    (log/debug :jdbc-execute!-res [result @w-msgs])
    [result @w-msgs]))

(comment
  (with-open [node (xtn/start-node config/node-config)]
    (doseq [st [#inst "2022" #inst "2021"]]
      (let [tx (xt/submit-tx node [] {:system-time st})
            results (xt/q node '(from :xt/txs [{:xt/id $tx-id} xt/error])
                          {:basis {:at-tx tx}
                           :args {:tx-id (:tx-id tx)}})]
        (when-let [error (-> results first :xt/error)]
          (throw (ex-info "Transaction error" {:error error})))))))
