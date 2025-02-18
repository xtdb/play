(ns xt-play.transactions
  (:require [clojure.string :as str]
            [jsonista.core :as j]
            [clojure.instant :refer [read-instant-date]]
            [clojure.tools.logging :as log]
            [xt-play.util :as util]
            [xt-play.xtdb :as xtdb]
            [xtdb.next.jdbc :as xjdbc]))

(defn- dml? [statement]
  (when statement
    (let [upper-st (str/upper-case statement)]
      (or (str/includes? upper-st "INSERT")
          (str/includes? upper-st "UPDATE")
          (str/includes? upper-st "DELETE")
          (str/includes? upper-st "ERASE")
          (str/includes? upper-st "MERGE")
          (str/includes? upper-st "PATCH")))))

(defn split-sql [sql]
  (loop [chars (seq sql) current [] statements [] in-string? false escape? false]
    (if (empty? chars)
      (conj statements (apply str current))
      (let [c (first chars)
            rest-chars (rest chars)]
        (cond
          (and (= c \') (not escape?))
          (recur rest-chars (conj current c) statements (not in-string?) false)

          (and in-string? (= c \\))
          (recur rest-chars (conj current c) statements in-string? (not escape?))

          (and (= c \;) (not in-string?))
          (recur rest-chars [] (conj statements (apply str current)) in-string? false)

          :else
          (recur rest-chars (conj current c) statements in-string? false))))))

(defn- encode-txs [tx-type query? txs]
  (case (keyword tx-type)
    :sql (if (and (not query?)
                  (string? txs))
           (->> (split-sql txs)
                (remove str/blank?)
                (map #(do [:sql %]))
                (vec))
           txs)
    :xtql (util/read-edn (if query? txs (str "[" txs "]")))
    ;;else
    txs))

(defn- transform-statements
  "Takes a batch of transactions and outputs the jdbc execution args to
  be run sequentially. It groups statements by type and wraps DMLs in explicit transactions if system time specified."
  [tx-batches]
  (for [{:keys [txs system-time]} tx-batches]
    (remove nil?
            (when txs
              (let [statements (split-sql txs)
                    by-type (partition-by dml? statements)]
                (log/warn "by-type" by-type)
                (mapcat
                 (fn [grp]
                   (let [dmls? (dml? (first grp))]
                     (concat
                      (when (and dmls? system-time)
                        [[(format "BEGIN READ WRITE WITH (SYSTEM_TIME = TIMESTAMP '%s')" system-time)]])
                      (vec
                       (keep (fn [q] (when-not (empty? q)
                                       [(str/trim q)]))
                             grp))
                      (when (and dmls? system-time)
                        [["COMMIT"]]))))
                 by-type))))))

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

(defn- detect-xtql-queries [batch]
  (if (:query batch)
    batch
    (if (and (string? (:txs batch))
             (re-matches #"(?i)^\s*(\(->)?\s*\((from|unify|rel).+" (:txs batch)))
      (assoc batch :query true)
      batch)))

(defn- run!-tx [node tx-type tx-batches]
  (let [tx-batches (->> tx-batches
                        (map detect-xtql-queries)
                        (map #(update % :system-time format-system-time))
                        (map #(update % :txs (partial encode-txs tx-type (:query %)))))
        tx-results
        (doall (mapv
                (fn [{:keys [system-time query txs] :as batch}]
                  (log/info tx-type "running batch: " batch)
                  (try
                    (if query
                      (xtdb/query node txs)
                      (xtdb/submit! node txs {:system-time system-time}))
                    (catch Throwable ex
                      (log/error "Exception while running transaction" (ex-message ex))
                      (parse-result
                       [{:message (ex-message ex)
                         :exception (.getClass ex)
                         :data (ex-data ex)}]))))
                tx-batches))]
    (log/warn "run!-tx-res" tx-results)
    tx-results))


(defn- run!-with-jdbc-conn [tx-batches]
  (xtdb/with-jdbc
    (fn [conn]
      (let [tx-in-progress? (atom false)
            res (mapv (fn [txs]
                        (vec
                         (mapcat
                          (fn [statement]
                            (log/info "beta executing statement:" statement)
                            (when (str/includes? (str/upper-case (first statement)) "BEGIN")
                              (reset! tx-in-progress? true))
                            (try
                              (let [res (parse-result (xtdb/jdbc-execute! conn statement))]
                                (when (str/includes? (str/upper-case (first statement)) "COMMIT")
                                  (reset! tx-in-progress? false))
                                (if-not (vector? (ffirst res))
                                  [res]
                                  res))
                              (catch Exception ex
                                (log/error "Exception while running statement" (ex-message ex))
                                (when @tx-in-progress?
                                  (log/warn "Rolling back transaction")
                                  (xtdb/jdbc-execute! conn ["ROLLBACK"]))
                                (parse-result
                                 [{:message (ex-message ex)
                                   :exception (.getClass ex)
                                   :data (ex-data ex)}]))))
                          txs)))
                      (transform-statements tx-batches))]
        (log/info "run!-with-jdbc-conn-res" res)
        res))))

(defn run!!
  "Given transaction batches, a query and the type of transaction to
  use, will run transaction batches and queries sequentially,
  returning the last query response in column format."
  [{:keys [tx-batches tx-type]}]
  (xtdb/with-xtdb
    (fn [node]
      (if (#{"sql-v2" "sql"} tx-type)
        (run!-with-jdbc-conn tx-batches)
        (let [res (run!-tx node tx-type tx-batches)]
          (log/warn "run!!" res)
          (mapv (comp vector util/map-results->rows) res))))))

(defn docs-run!!
  "Given transaction batches and a query from the docs, will return the query
  response in map format. Assumes tx type is sql."
  [{:keys [tx-batches query]}]
  (xtdb/with-xtdb
    (fn [node]
      (run!-tx node "sql"
               (mapv #(update % :txs util/read-edn) tx-batches))
      (let [res (run!-tx node "sql"
                         [{:txs (util/read-edn query) :query true}])]
        (first res)))))
