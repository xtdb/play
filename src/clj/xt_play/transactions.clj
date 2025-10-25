(ns xt-play.transactions
  (:require [clojure.string :as str]
            [clojure.instant :refer [read-instant-date]]
            [clojure.tools.logging :as log]
            [xt-play.util :as util]
            [xt-play.xtdb :as xtdb]))

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
             (mapv parse-PG-array (vals row)))
           result))))

(defn- detect-xtql-queries [batch]
  (if (:query batch)
    batch
    (if (and (string? (:txs batch))
             ;; First check it's NOT a DML statement
             (not (re-find #"(?i)^\s*(INSERT|UPDATE|DELETE|ERASE|MERGE|PATCH)\b" (:txs batch)))
             (or
              ;; Detect XTQL queries
              (re-matches #"(?i)^\s*(\(->)?\s*\((from|unify|rel).+" (:txs batch))
              ;; Detect SQL SELECT queries (including CTEs)
              ;; Use (?s) DOTALL flag so . matches newlines for multiline queries
              (re-matches #"(?is)^\s*(WITH\s+.+\s+)?SELECT\s+.+" (:txs batch))
              ;; Detect bare FROM queries (table expressions)
              (re-matches #"(?is)^\s*FROM\s+.+" (:txs batch))))
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

(defn- run!-with-jdbc-conn [tx-batches]
  (xtdb/with-jdbc
    (fn [conn]
      (let [tx-in-progress? (atom false)
            res (mapv (fn [txs]
                        (mapv
                         (fn [statement]
                           (log/debug "beta executing statement:" statement)
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
                                        :data (ex-data ex)}})))
                         txs))
                      (transform-statements tx-batches))]
        (log/debug "run!-with-jdbc-conn-res" res)
        res))))

(defn run!!
  "Given transaction batches, a query and the type of transaction to
  use, will run transaction batches and queries sequentially,
  returning the last query response in column format."
  [{:keys [tx-batches tx-type]}]
  (if (#{"sql-v2" "sql"} tx-type)
    ;; For SQL: use xtdb.api for both queries and DML
    (xtdb/with-xtdb
      (fn [node]
        ;; First, expand each batch by splitting on semicolons and track original indices
        (let [expanded-with-indices (mapcat
                                     (fn [idx {:keys [txs system-time query] :as batch}]
                                       (if (and (string? txs) (not query))
                                          ;; Split multi-statement batches (only if not already marked as query)
                                         (let [statements (split-sql txs)]
                                           (mapv (fn [stmt]
                                                   {:txs stmt
                                                    :system-time system-time
                                                    :original-idx idx})
                                                 statements))
                                          ;; Keep single batches as-is
                                         [(assoc batch :original-idx idx)]))
                                     (range)
                                     tx-batches)
              batches-with-detection (mapv detect-xtql-queries expanded-with-indices)
              results (mapv
                       (fn [{:keys [txs system-time query] :as batch}]
                         (try
                           (let [tx-opts (cond-> {}
                                           system-time (assoc :system-time (format-system-time system-time)))]
                             (if query
                               ;; Use xt/q for queries
                               (let [result (xtdb/query node txs)]
                                 (assoc batch :result (util/map-results->rows result)))
                               ;; Use xt/execute-tx for DML - blocks until indexed
                               (let [tx-ops [[:sql txs]]
                                     tx-result (xtdb/execute-tx! node tx-ops tx-opts)]
                                 ;; Format the tx result nicely
                                 (assoc batch :result [{:result [["tx-id" "system-time"]
                                                                 [(:tx-id tx-result)
                                                                  (str "TIMESTAMP '" (:system-time tx-result) "'")]]}]))))
                           (catch Throwable ex
                             (log/error "Exception while running transaction" (ex-message ex))
                             (assoc batch :result {:error {:message (ex-message ex)
                                                           :exception (.getClass ex)
                                                           :data (ex-data ex)}}))))
                       batches-with-detection)]
          ;; Group results back by original batch index
          (mapv (fn [idx]
                  (let [batch-results (filter #(= idx (:original-idx %)) results)]
                    (mapcat :result batch-results)))
                (range (count tx-batches))))))
    ;; For non-SQL (XTQL), use direct API
    (xtdb/with-xtdb
      (fn [node]
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
