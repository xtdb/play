(ns xt-play.query)

;; >> API

(def default-xtql-query "(from :docs [xt/id foo])")
(def default-sql-query "SELECT docs.xt$id, docs.foo FROM docs")

(defn default [type]
  (case type
    :xtql default-xtql-query
    :sql default-sql-query))
