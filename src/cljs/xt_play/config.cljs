(ns xt-play.config)

(def ^:private default-dml
  "[:put-docs :docs {:xt/id 1 :foo \"bar\"}]")

(def ^:private default-xtql-query
  "(from :docs [xt/id foo])")

(def ^:private default-sql-insert
  "INSERT INTO docs (_id, col1) VALUES (1, 'foo');
INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};")

(def ^:private default-sql-query
  "SELECT *, _valid_from FROM docs;")

(def default-transaction
  {:sql-v2 default-sql-insert
   :xtql default-dml})

(def default-query
  {:sql-v2 default-sql-query
   :xtql default-xtql-query})

(def tx-types
  {:sql-v2 {:value :sql-v2
            :label "SQL"}
   :xtql {:value :xtql
          :label "XTQL"}})
