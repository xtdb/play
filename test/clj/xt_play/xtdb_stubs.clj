(ns xt-play.xtdb-stubs
  (:require [clojure.tools.logging :as log]
            [xt-play.xtdb :as xtdb]))

(def db (atom []))
(defn- execute! [statement] (swap! db conj statement))
(defn clean-db [] (reset! db []))

(def resp (atom nil))
(defn mock-resp [response] (reset! resp response))
(defn reset-resp [] (mock-resp nil))

(defn with-xtdb [f] (f nil))

(defn submit! [_node txs opts]
  (log/info :stub-submit-tx txs opts)
  (execute! txs))

(defn execute-tx! [_node tx-ops opts]
  (log/info :stub-execute-tx tx-ops opts)
  ;; Record both tx-ops and opts so tests can verify system-time
  (execute! {:tx-ops tx-ops :opts opts})
  {:tx-id 0 :system-time (or (:system-time opts) #inst "2024-01-01T00:00:00.000-00:00")})

(defn query [_node q]
  (log/info :stub-query q)
  (execute! q)
  (or
   @resp
   [[{:xt/id 2, :col1 "bar", :col2 " baz"} {:xt/id 1, :col1 "foo"}]
    []]))

(defn with-jdbc [f] (f nil))

(defn jdbc-execute! [_conn statement]
  (log/info :stub-jdbc-execute! statement)
  (execute! statement)
  (or @resp
      [[{:xt/id 2 :col1 "bar" :col2 " baz"} {:xt/id 1 :col1 "foo" :col2 nil}]
       []]))

(defn with-stubs [f]
  (with-redefs [xtdb/with-xtdb with-xtdb
                xtdb/with-jdbc with-jdbc
                xtdb/submit! submit!
                xtdb/execute-tx! execute-tx!
                xtdb/query query
                xtdb/jdbc-execute! jdbc-execute!]
    (f)))
