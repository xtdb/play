(ns ^:integration xt-play.integration-test
  (:require [clojure.edn :as edn]
            [clojure.test :as t]
            [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [xt-play.handler :as h]
            [xtdb.api :as xt]))

;; todo - this should be purely testing xt responses, not the handler.
;; can share the expected tx data from the results to keep in sync with changes

(defn- t-file [path]
  (edn/read-string (slurp (format "test-resources/%s.edn" path))))

(t/deftest run-handler-test
  (t/testing "xtql example returns expected results"
    (t/is (= {:status 200, :body [[:foo :xt/id] ["bar" 1]]}
             (h/run-handler (t-file "xtql-example-request")))))

  (t/testing "sql example returns expected results"
    (t/is (= {:status 200,
              :body
              [["_id" "col1" "col2"]
               [2 "bar" " baz"]
               [1 "foo" nil]]}
             (h/run-handler (t-file "sql-example-request")))))

  (t/testing "beta sql example returns expected results"
    (t/is (= {:status 200,
              :body
              [[:_id :col1 :col2]
               [2 "bar" " baz"]
               [1 "foo" nil]]}
             (h/run-handler (t-file "beta-sql-example-request"))))))

(t/deftest run-handler-multi-transactions-test
  (t/testing "multiple transactions in xtql"
    (t/is (= {:status 200, :body [[:foo :xt/id]
                                  ["baz" 2]
                                  ["bar" 1]]}
             (h/run-handler
              (assoc-in
               (t-file "xtql-example-request")
               [:parameters :body :tx-batches]
               [{:txs "[:put-docs :docs {:xt/id 1 :foo \"bar\"}]",
                 :system-time "2024-12-01T00:00:00.000Z"}
                {:txs "[:put-docs :docs {:xt/id 2 :foo \"baz\"}]",
                 :system-time nil}])))))

  (t/testing "multiple transacions on sql"
    (t/is (= {:status 200,
              :body
              [["_id" "col1" "col2" "_valid_from"]
               [2 "bar" " baz" #time/zoned-date-time "2024-12-02T00:00Z[UTC]"]
               [1 "foo" nil #time/zoned-date-time "2024-12-01T00:00Z[UTC]"]]}
             (h/run-handler
              (-> (t-file "sql-example-request")
                  (assoc-in
                   [:parameters :body :tx-batches]
                   [{:txs "INSERT INTO docs (_id, col1) VALUES (1, 'foo');",
                     :system-time "2024-12-01T00:00:00.000Z"}
                    {:txs "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};",
                     :system-time "2024-12-02T00:00:00.000Z"}])
                  (assoc-in
                   [:parameters :body :query]
                   "SELECT *, _valid_from FROM docs"))))))

  (t/testing "beta sql can run multiple txs"
    (t/is (= {:status 200,
              :body
              [[:_id :col1 :col2 :_valid_from]
               [2 "bar" " baz" #inst "2024-12-02T00:00:00.000000000-00:00"]
               [1 "foo" nil #inst "2024-12-01T00:00:00.000000000-00:00"]]}
             (h/run-handler
              (-> (t-file "beta-sql-example-request")
                  (assoc-in
                   [:parameters :body :tx-batches]
                   [{:txs "INSERT INTO docs (_id, col1) VALUES (1, 'foo');",
                     :system-time "2024-12-01T00:00:00.000Z"}
                    {:txs "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};",
                     :system-time "2024-12-02T00:00:00.000Z"}])
                  (assoc-in
                   [:parameters :body :query]
                   "SELECT *, _valid_from FROM docs")))))))

(t/deftest beta-sql-run-features
  (t/testing "Column order is maintained"
    (t/is (= {:status 200,
              :body [[:_id :a :b :c :d :e :f :g :h :j]
                     [1 2 3 4 5 6 7 8 9 10]]}
             (h/run-handler
              (assoc-in
               (t-file "beta-sql-example-request")
               [:parameters :body :tx-batches]
               [{:txs "INSERT INTO docs RECORDS {_id: 1, a: 2, b: 3, c: 4, d: 5, e: 6, f: 7, g: 8, h: 9, j: 10}"
                 :system-time nil}])))))

  (t/testing "execute payload is not mutated"
    (let [txs (atom [])]
      (with-redefs [jdbc/execute! (fn [_conn statement & _args]
                                    (swap! txs conj statement))]
        (h/run-handler (t-file "beta-sql-example-request"))
        (t/is
         (= [[(str "INSERT INTO docs (_id, col1) VALUES (1, 'foo');\n"
                   "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};")]
             ["SELECT * FROM docs"]]
            @txs)))))

  (t/testing "xt submit-tx sql payload is reformatted"
    (let [txs (atom [])]
      (with-redefs [xt/submit-tx (fn [_node tx & _args]
                                   (swap! txs conj tx))]
        (h/run-handler (t-file "sql-example-request"))
        (t/is
         (= [[[:sql "INSERT INTO docs (_id, col1) VALUES (1, 'foo')"]
              [:sql "\nINSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'}"]]]
            @txs))))))

(t/deftest sql-says-carol-is-red-test
  (t/testing "XTDB docs example for sql https://docs.xtdb.com/quickstart/sql-overview.html"
    (t/is (= {:status 200,
              :body
              [["name" "favorite_color" "_valid_from" "_system_from"]
               ["carol"
                "red"
                #time/zoned-date-time "2023-09-01T00:00Z[UTC]"
                #time/zoned-date-time "2024-01-08T00:00Z[UTC]"]]}
             (h/run-handler (t-file "sql-multi-transaction")))))

  (t/testing "Bob still likes fishing - don't determine columns based on the first row"
    (t/is (= {:status 200,
              :body
              [["_id" "favorite_color" "name" "likes"]
               [2 "red" "carol" nil]
               [9 nil "bob" ["fishing" 3.14 {"nested" "data"}]]]}
             (h/run-handler
              (assoc-in
               (t-file "sql-multi-transaction")
               [:parameters :body :query]
               "SELECT * FROM people"))))))

(t/deftest beta-sql-says-carol-is-red-test
  (t/testing "XTDB docs example for sql https://docs.xtdb.com/quickstart/sql-overview.html"
    (t/is (= {:status 200,
              :body
              [[:name :favorite_color :_valid_from :_system_from]
               ["carol"
                "red"
                #inst "2023-08-31T23:00:00.000000000-00:00"
                #inst "2024-01-08T00:00:00.000000000-00:00"]]}
             (h/run-handler (assoc-in
                             (t-file "sql-multi-transaction")
                             [:parameters :body :tx-type]
                             "sql-beta")))))

  (t/testing "Bob still likes fishing - don't determine columns based on the first row"
    (t/is (= {:status 200,
             :body
             [[:_id :favorite_color :info :likes :name]
              [2 "red" nil nil "carol"]
              [9 nil nil ["fishing" 3.14 {:nested "data"}] "bob"]]}
             (h/run-handler
              (->  (t-file "sql-multi-transaction")
                   (assoc-in [:parameters :body :query] "SELECT * FROM people")
                   (assoc-in [:parameters :body :tx-type] "sql-beta")))))))

(def docs-json
 "{\"tx-batches\":[{\"txs\":\"[[:sql \\\"INSERT INTO product (_id, name, price) VALUES\\\\n(1, 'An Electric Bicycle', 400)\\\"]]\",\"system-time\":\"2024-01-01\"},{\"txs\":\"[[:sql \\\"UPDATE product SET price = 405 WHERE _id = 1\\\"]]\",\"system-time\":\"2024-01-05\"},{\"txs\":\"[[:sql \\\"UPDATE product SET price = 350 WHERE _id = 1\\\"]]\",\"system-time\":\"2024-01-10\"}],\"query\":\"\\\"SELECT *, _valid_from\\\\nFROM product\\\\nFOR VALID_TIME ALL -- i.e. \\\\\\\"show me all versions\\\\\\\"\\\\nFOR SYSTEM_TIME AS OF DATE '2024-01-31' -- \\\\\\\"...as observed at month end\\\\\\\"\\\"\"}"
)

(t/deftest docs-run
  (let [response (h/docs-run-handler {:parameters {:body (json/read-str docs-json :key-fn keyword)}})]
    (t/testing "docs run returns map results"
      (t/is
       (every? map? (:body response))))

    (t/testing "can handle \" strings from docs"
      (t/is (= {:status 200,
                :body
                [{"_id" 1,
                  "name" "An Electric Bicycle",
                  "price" 350,
                  "_valid_from" #time/zoned-date-time "2024-01-10T00:00Z[UTC]"}
                 {"_id" 1,
                  "name" "An Electric Bicycle",
                  "price" 405,
                  "_valid_from" #time/zoned-date-time "2024-01-05T00:00Z[UTC]"}
                 {"_id" 1,
                  "name" "An Electric Bicycle",
                  "price" 400,
                  "_valid_from" #time/zoned-date-time "2024-01-01T00:00Z[UTC]"}]}
               response)))))
