(ns xt-play.transactions
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.instant :refer [read-instant-date]]
            [clojure.tools.logging :as log]
            [xt-play.util :as util]
            [xt-play.xtdb :as xtdb]))

(defn- encode-txs [tx-type txs]
  (case (keyword tx-type)
    :sql (if (string? txs)
           (->> (str/split txs #";")
                (remove str/blank?)
                (map #(do [:sql %]))
                (vec))
           txs)
    :xtql (util/read-edn (str "[" txs "]"))
    ;;else
    txs))

(defn- prepare-statements
  "Takes a batch of transactions and prepares the jdbc execution args to
  be run sequentially"
  [tx-batches]
  (for [{:keys [txs system-time]} tx-batches]
    (remove nil?
     [(when system-time
        [(format "BEGIN AT SYSTEM_TIME TIMESTAMP '%s'" system-time)])
      [txs]
      (when system-time
        ["COMMIT"])])))

(defn format-system-time [s]
  (when s (read-instant-date s)))

(defn- PGobject->clj [v]
  (if (= org.postgresql.util.PGobject (type v))
    (json/read-str (.getValue v) :key-fn keyword)
    v))

(defn- parse-result [result]
  ;; TODO - this shouldn't be needed, a fix is on the way in
  ;;        a later version of xtdb-jdb
  ;; This will only pick up top level objects
  (mapv
   (fn [row]
     (mapv PGobject->clj row))
   result))

(defn- run!-tx [node tx-type tx-batches query]
  (let [tx-batches (->> tx-batches
                        (map #(update % :system-time format-system-time))
                        (map #(update % :txs (partial encode-txs tx-type))))]
    (doseq [{:keys [system-time txs] :as batch} tx-batches]
      (log/info tx-type "running batch: " batch)
      (xtdb/submit! node txs {:system-time system-time})))
  (log/info tx-type "running query:" query)
  (let [res (xtdb/query node query)]
    (log/info tx-type "XTDB query response:" res)
    res))

(defn- run!-with-jdbc-conn [tx-batches query]
  (xtdb/with-jdbc
    (fn [conn]
      (doseq [tx (prepare-statements tx-batches)
              statement tx]
        (log/info "beta executing statement:" statement)
        (xtdb/jdbc-execute! conn statement))
      (log/info "beta running query:" query)
      (let [res (xtdb/jdbc-execute! conn [query])]
        (log/info "beta query resoponse" res)
        (parse-result res)))))

(defn run!!
  "Given transaction batches, a query and the type of transaction to
  use, will run transaction batches and queries sequentially,
  returning the last query response in column format."
  [{:keys [tx-batches query tx-type]}]
  (let [query (if (= "xtql" tx-type) (util/read-edn query) query)]
    (xtdb/with-xtdb
      (fn [node]
        (if (= "sql-beta" tx-type)
          (run!-with-jdbc-conn tx-batches query)
          (let [res (run!-tx node tx-type tx-batches query)]
            (util/map-results->rows res)))))))

(defn docs-run!!
  "Given transaction batches and a query from the docs, will return the query
  response in map format. Assumes tx type is sql."
  [{:keys [tx-batches query]}]
  (xtdb/with-xtdb
    (fn [node]
      (run!-tx node "sql"
               (mapv #(update % :txs util/read-edn) tx-batches)
               (util/read-edn query)))))

