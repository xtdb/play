(ns xt-play.transactions
  (:require [clojure.string :as str]
            [clojure.instant :refer [read-instant-date]]
            [clojure.tools.logging :as log]
            [xt-play.util :as util]
            [xt-play.xtdb :as xtdb]
            [xtdb.db-catalog :as db-catalog]))

(defn- dml? [statement]
  (when statement
    (let [upper-st (str/upper-case statement)]
      (or (str/includes? upper-st "INSERT")
          (str/includes? upper-st "UPDATE")
          (str/includes? upper-st "DELETE")
          (str/includes? upper-st "ERASE")
          (str/includes? upper-st "MERGE")
          (str/includes? upper-st "PATCH")))))

(defn- pragma-statement?
  "Detects PRAGMA statements and returns the pragma type if found."
  [statement]
  (when statement
    (let [upper-st (str/trim (str/upper-case statement))]
      (when (str/starts-with? upper-st "PRAGMA")
        (cond
          (re-find #"PRAGMA\s+FINISH_BLOCK" upper-st) :finish-block
          :else :unknown)))))

(defn split-sql [sql]
  (loop [chars (seq sql)
         current []
         statements []
         in-string? false
         escape? false
         in-comment? false] ;; this later becomes character that started the comment
    (if (empty? chars)
      ;; finished parsing - add last statement and remove empty ones
      (->> (conj statements (apply str current))
           (map str/trim)
           (filter seq))
      (let [c1 (first chars)
            rest-chars (rest chars)
            c2 (second chars)]
        (cond
          ;; start -- comment
          (and (not in-comment?) (= c1 \-) (= c2 \-))
          (recur (rest rest-chars) current statements in-string? escape? \-)

          ;; ignore till end of line
          (and (= \- in-comment?) (not= c1 \newline))
          (recur rest-chars current statements in-string? escape? \-)

          ;; end of -- comment
          (and (= \- in-comment?) (= c1 \newline))
          (recur rest-chars current statements in-string? escape? false)

          ;; start /* comment */
          (and (not in-comment?) (= c1 \/) (= c2 \*))
          (recur (rest (rest rest-chars)) current statements in-string? escape? \*)

          ;; end of /* comment */
          (and in-comment? (and (= c1 \*) (= c2 \/)))
          (recur (rest rest-chars) current statements in-string? escape? false)

          ;; ignore till end of /* comment */
          (= \* in-comment?)
          (recur rest-chars current statements in-string? escape? \*)

          (and (= c1 \') (not escape?) (not in-comment?))
          (recur rest-chars (conj current c1) statements (not in-string?) false in-comment?)

          (and in-string? (= c1 \\))
          (recur rest-chars (conj current c1) statements in-string? (not escape?) in-comment?)

          (and (= c1 \;) (not in-string?) (not in-comment?))
          (recur rest-chars [] (conj statements (apply str current)) in-string? false in-comment?)

          :else
          (recur rest-chars (conj current c1) statements in-string? false in-comment?))))))

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

(defn- parse-PG-array [v]
  (if
   (instance? org.postgresql.jdbc.PgArray v)
    (->> (.getArray v)
         (into []))
    v))

(defn- xform-result [result]
  (let [columns (keys (first result))]
    (into [columns]
          (mapv
           (fn [row]
             (mapv (fn [v]
                     (let [parsed (parse-PG-array v)]
                       ;; Log the type before transformation
                       (when (not (string? parsed))
                         (log/debug "JDBC value type:" (type parsed) "value:" parsed))
                       (util/transform-dates-to-sql parsed)))
                   (vals row)))
           result))))

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
                  (log/debug tx-type "running batch: " batch)
                  (try
                    (if query
                      (xtdb/query node txs)
                      (xtdb/submit! node txs {:system-time system-time}))
                    (catch Throwable ex
                      (log/error "Exception while running transaction" (ex-message ex))
                      {:error {:message (ex-message ex)
                               :exception (.getClass ex)
                               :data (ex-data ex)}})))
                tx-batches))]
    (log/info "run!-tx-res" tx-results)
    tx-results))

(defn- run!-with-jdbc-conn [node tx-batches]
  (with-open [conn (xtdb/get-node-connection node)]
    (let [tx-in-progress? (atom false)
          res (mapv (fn [txs]
                      (mapv
                       (fn [statement]
                         (log/debug "beta executing statement:" statement)
                         ;; Check if this is a PRAGMA statement
                         (if-let [pragma (pragma-statement? (first statement))]
                           ;; Handle PRAGMA statements
                           (case pragma
                             :finish-block
                             (do
                               (log/info "Executing PRAGMA finish_block")
                               (.finishBlock (.getLogProcessor (db-catalog/primary-db node)))
                               {:result [[:status] ["Block finished"]]
                                :warnings []})
                             ;; Unknown pragma
                             {:error {:message (str "Unknown PRAGMA: " pragma)}})
                           ;; Handle regular SQL statements
                           (do
                             (when (str/includes? (str/upper-case (first statement)) "BEGIN")
                               (reset! tx-in-progress? true))
                             (try
                               (let [[rs warnings] (xtdb/jdbc-execute! conn statement)
                                     res (xform-result rs)]
                                 (when (str/includes? (str/upper-case (first statement)) "COMMIT")
                                   (reset! tx-in-progress? false))
                                 (log/info :run-with-jdbc-conn-warnings warnings)
                                 {:result res
                                  :warnings warnings})
                               (catch Exception ex
                                 (log/error "Exception while running statement" (ex-message ex))
                                 (when @tx-in-progress?
                                   (log/warn "Rolling back transaction")
                                   (xtdb/jdbc-execute! conn ["ROLLBACK"]))
                                 {:error {:message (ex-message ex)
                                          :exception (.getClass ex)
                                          :data (ex-data ex)}})))))
                       txs))
                    (transform-statements tx-batches))]
      (log/debug "run!-with-jdbc-conn-res" res)
      res)))

(defn run!!
  "Given transaction batches, a query and the type of transaction to
  use, will run transaction batches and queries sequentially,
  returning the last query response in column format."
  [{:keys [tx-batches tx-type]}]
  (xtdb/with-xtdb
    (fn [node]
      (if (#{"sql-v2" "sql"} tx-type)
        (run!-with-jdbc-conn node tx-batches)
        (let [res (run!-tx node tx-type tx-batches)]
          (log/debug "run!!" res)
          (mapv util/map-results->rows res))))))

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
