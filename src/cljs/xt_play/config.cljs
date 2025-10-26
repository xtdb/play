(ns xt-play.config)

(def default-sql-insert
  "INSERT INTO docs (_id, col1) VALUES (1, 'foo');
INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};")

(def default-sql-query
  "SELECT *, _valid_from FROM docs;")

(def default-transaction default-sql-insert)

(def default-query default-sql-query)
