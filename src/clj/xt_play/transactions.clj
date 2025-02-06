(ns xt-play.transactions
  (:require [clojure.string :as str]
            [jsonista.core :as j]
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
            (when txs
              (concat
               (when system-time
                 [[(format "BEGIN AT SYSTEM_TIME TIMESTAMP '%s'" system-time)]])
               (vec
                (keep (fn [q] (when-not (empty? q)
                                (vector (str/trim q))))
                      (str/split txs #"\s*;\s*")))
               (when system-time
                 [["COMMIT"]]))))))

(defn format-system-time [s]
  (when s (read-instant-date s)))

(defn- PG->clj [v]
  (cond
    (instance? org.postgresql.util.PGobject v) (-> (.getValue v)
                                                   (j/read-value j/keyword-keys-object-mapper))
    (instance? org.postgresql.jdbc.PgArray v) (->> (.getArray v)
                                                   (into [])
                                                   (str/join ",")
                                                   (format "[%s]"))
    :else v))

(defn- parse-result [result]
  ;; TODO - this shouldn't be needed, a fix is on the way in
  ;;        a later version of xtdb-jdb
  ;; This should do it for now - get decent string representation so it looks more like psql output
  (mapv
   (fn [row]
     (mapv PG->clj row))
   result))

(defn- run!-tx [node tx-type tx-batches query]
  (let [tx-batches (->> tx-batches
                        (map #(update % :system-time format-system-time))
                        (map #(update % :txs (partial encode-txs tx-type))))
        tx-results
        (doall (mapv
                (fn [{:keys [system-time txs] :as batch}]
                  (log/info tx-type "running batch: " batch)
                  (xtdb/submit! node txs {:system-time system-time}))
                tx-batches))]
    (log/info tx-type "running query:" query)
    (let [res (xtdb/query node query)]
      (log/info tx-type "XTDB query response:" res)
      [tx-results res])))


(defn- run!-with-jdbc-conn [tx-batches query]
  (xtdb/with-jdbc
    (fn [conn]
      (let [tx-results
            (vec
             (doall
              (map (fn [txs]
                     (vec
                      (mapcat (fn [statement]
                                (log/info "beta executing statement:" statement)
                                (try
                                  (let [st-res (xtdb/jdbc-execute! conn statement)]
                                    ;; hack to get it into right shape - TXs seem to always return
                                    ;; {:next.jdbc/update-count 0} - which comes out wrapped one times too many
                                    (first (parse-result st-res)))
                                  (catch Exception ex
                                    (log/error "Exception while running statement" ex)
                                    {:message (ex-message ex)
                                     :exception (.getClass ex)
                                     :data (ex-data ex)})))
                              txs)))
                   (prepare-statements tx-batches))))]
        (log/info "beta running query:" query)
        (try
          (let [res (xtdb/jdbc-execute! conn [query])]
            (log/info "beta query response" res)
            (into tx-results [(parse-result res)]))
          (catch Exception ex
            (log/error "Exception running query" (ex-message ex))
            (into tx-results [(parse-result
                               {:message (ex-message ex)
                                :exception (.getClass ex)
                                :data (ex-data ex)})])))))))

(defn run!!
  "Given transaction batches, a query and the type of transaction to
  use, will run transaction batches and queries sequentially,
  returning the last query response in column format."
  [{:keys [tx-batches query tx-type]}]
  (let [query (if (= "xtql" tx-type) (util/read-edn query) query)]
    (xtdb/with-xtdb
      (fn [node]
        (if (= "sql-v2" tx-type)
          (run!-with-jdbc-conn tx-batches query)
          (let [res (run!-tx node tx-type tx-batches query)]
            [(first res) (util/map-results->rows (last res))]))))))

(defn docs-run!!
  "Given transaction batches and a query from the docs, will return the query
  response in map format. Assumes tx type is sql."
  [{:keys [tx-batches query]}]
  (xtdb/with-xtdb
    (fn [node]
      (run!-tx node "sql"
               (mapv #(update % :txs util/read-edn) tx-batches)
               (util/read-edn query)))))
