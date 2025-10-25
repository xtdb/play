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

(defn- parse-begin-statement
  "Extracts system time from BEGIN statement if present.
   Returns nil if not a BEGIN statement, or a map with :system-time if it is."
  [statement]
  (when statement
    (let [upper-st (str/trim (str/upper-case statement))]
      (when (str/starts-with? upper-st "BEGIN")
        (if-let [match (re-find #"SYSTEM_TIME\s*=\s*TIMESTAMP\s+'([^']+)'" statement)]
          {:system-time (second match)}
          {})))))

(defn- commit-statement? [statement]
  (when statement
    (let [upper-st (str/trim (str/upper-case statement))]
      (str/starts-with? upper-st "COMMIT"))))

(defn- pragma-statement? [statement]
  "Detects PRAGMA statements and returns the pragma type if found."
  (when statement
    (let [upper-st (str/trim (str/upper-case statement))]
      (when (str/starts-with? upper-st "PRAGMA")
        (cond
          (re-find #"PRAGMA\s+FINISH_BLOCK" upper-st) :finish-block
          :else :unknown)))))

(defn- group-into-transactions
  "Groups statements into transactions based on BEGIN/COMMIT boundaries.
   Returns a sequence of maps with :statements (vector), :system-time (optional), :pragma (optional), and :query (boolean).
   BEGIN, COMMIT, and PRAGMA statements are filtered out and handled specially."
  [statements]
  (loop [remaining statements
         current-tx nil
         grouped []]
    (if (empty? remaining)
      ;; Flush any remaining transaction
      (if current-tx
        (conj grouped current-tx)
        grouped)
      (let [stmt (first remaining)
            rest-stmts (rest remaining)
            begin-info (parse-begin-statement stmt)
            pragma-type (pragma-statement? stmt)]
        (cond
          ;; Found a BEGIN - start a new transaction (don't include BEGIN in statements)
          begin-info
          (recur rest-stmts
                 {:statements []
                  :system-time (:system-time begin-info)}
                 ;; Flush previous transaction if exists
                 (if current-tx (conj grouped current-tx) grouped))

          ;; Found a COMMIT - end current transaction (don't include COMMIT in statements)
          (commit-statement? stmt)
          (recur rest-stmts
                 nil
                 (if current-tx (conj grouped current-tx) grouped))

          ;; Found a PRAGMA - treat as special standalone statement
          pragma-type
          (recur rest-stmts
                 nil
                 (conj (if current-tx (conj grouped current-tx) grouped)
                       {:statements [] :pragma pragma-type}))

          ;; Regular statement - add to appropriate group
          :else
          (if current-tx
            ;; Add to current transaction
            (recur rest-stmts
                   (update current-tx :statements conj stmt)
                   grouped)
            ;; No active transaction - treat as standalone
            (recur rest-stmts
                   nil
                   (conj grouped {:statements [stmt]}))))))))

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
    (let [txs (:txs batch)
          ;; If txs is a vector, check if all statements are queries
          ;; If txs is a string, check if it's a query
          is-query? (if (vector? txs)
                      ;; For vectors, check if the single statement (or all statements) are queries
                      ;; In practice, grouped transactions should have already separated queries
                      (and (= 1 (count txs))
                           (let [stmt (first txs)]
                             (and (string? stmt)
                                  (not (re-find #"(?i)^\s*(INSERT|UPDATE|DELETE|ERASE|MERGE|PATCH)\b" stmt))
                                  (or
                                   (re-matches #"(?i)^\s*(\(->)?\s*\((from|unify|rel).+" stmt)
                                   (re-matches #"(?is)^\s*(WITH\s+.+\s+)?SELECT\s+.+" stmt)
                                   (re-matches #"(?is)^\s*FROM\s+.+" stmt)))))
                      ;; For strings, use original logic
                      (and (string? txs)
                           (not (re-find #"(?i)^\s*(INSERT|UPDATE|DELETE|ERASE|MERGE|PATCH)\b" txs))
                           (or
                            (re-matches #"(?i)^\s*(\(->)?\s*\((from|unify|rel).+" txs)
                            (re-matches #"(?is)^\s*(WITH\s+.+\s+)?SELECT\s+.+" txs)
                            (re-matches #"(?is)^\s*FROM\s+.+" txs))))]
      (if is-query?
        (assoc batch :query true)
        batch))))

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

(defn run!!
  "Given transaction batches, a query and the type of transaction to
  use, will run transaction batches and queries sequentially,
  returning the last query response in column format."
  [{:keys [tx-batches tx-type]}]
  (if (#{"sql-v2" "sql"} tx-type)
    ;; For SQL: use xtdb.api for both queries and DML
    (xtdb/with-xtdb
      (fn [node]
        ;; Process each batch, splitting statements and grouping by transactions
        (let [expanded-with-indices
              (mapcat
               (fn [idx {:keys [txs system-time query] :as batch}]
                 (if (and (string? txs) (not query))
                   ;; Split multi-statement batches and group by BEGIN/COMMIT
                   (let [statements (split-sql txs)
                         tx-groups (group-into-transactions statements)]
                     (mapv (fn [tx-group]
                             {:txs (:statements tx-group)
                              ;; Use system-time from BEGIN if present, otherwise from batch
                              :system-time (or (:system-time tx-group) system-time)
                              :original-idx idx
                              :pragma (:pragma tx-group)
                              :multi-statement (> (count (:statements tx-group)) 1)})
                           tx-groups))
                   ;; Keep query batches as-is
                   [(assoc batch :original-idx idx)]))
               (range)
               tx-batches)

              batches-with-detection (mapv detect-xtql-queries expanded-with-indices)

              results
              (mapv
               (fn [{:keys [txs system-time query multi-statement pragma] :as batch}]
                 (try
                   (cond
                     ;; Handle PRAGMA statements
                     pragma
                     (case pragma
                       :finish-block
                       (do
                         (.finishBlock (.getLogProcessor (db-catalog/primary-db node)))
                         (assoc batch :result [{:result [["status"] ["Block finished"]]}]))
                       ;; Unknown pragma
                       (assoc batch :result [{:error {:message (str "Unknown PRAGMA: " pragma)}}]))

                     ;; Handle queries
                     query
                     (let [result (xtdb/query node (if (vector? txs) (first txs) txs))]
                       (assoc batch :result (util/map-results->rows result)))

                     ;; Handle DML
                     :else
                     (let [tx-opts (cond-> {}
                                     system-time (assoc :system-time (format-system-time system-time)))
                           ;; For multi-statement transactions, wrap all in [:sql ...] ops
                           tx-ops (if multi-statement
                                    (mapv (fn [stmt] [:sql stmt]) txs)
                                    [[:sql (if (vector? txs) (first txs) txs)]])
                           tx-result (xtdb/execute-tx! node tx-ops tx-opts)]
                       ;; Format the tx result nicely
                       (assoc batch :result [{:result [["tx-id" "system-time"]
                                                       [(:tx-id tx-result)
                                                        (str "TIMESTAMP '" (:system-time tx-result) "'")]]}])))
                   (catch Throwable ex
                     (log/error "Exception while running transaction" (ex-message ex))
                     (assoc batch :result [{:error {:message (ex-message ex)
                                                    :exception (.getClass ex)
                                                    :data (ex-data ex)}}]))))
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
