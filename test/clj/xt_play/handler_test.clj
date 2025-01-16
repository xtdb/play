(ns xt-play.handler-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t]
            [clojure.data.json :as json]
            [xt-play.handler :as h]
            [xt-play.xtdb-stubs :refer [with-stubs db clean-db mock-resp reset-resp]]))
;; todo:
;; [ ] test unhappy paths
;; [ ] test wider range of scenarios / formats
;; [x] test to pipeline
;; [ ] assert format from client
;; [x] stub xtdb
;; [x] integration tests with xtdb


(defn- t-file [path]
  (edn/read-string (slurp (format "test-resources/%s.edn" path))))
#_
(t/deftest run-handler-test
  (with-stubs
    #(do
       (t/testing "xtql example sends expected payload to xtdb"
         (clean-db)
         (h/run-handler (t-file "xtql-example-request"))
         (let [[txs query] @db]
           (t/is (= 2 (count @db)))
           (t/is (= [[:put-docs :docs {:xt/id 1, :foo "bar"}]]
                    txs))
           (t/is (= '(from :docs [xt/id foo])
                    query))))

       (t/testing "sql example sends expected payload to xtdb"
         (clean-db)
         (h/run-handler (t-file "sql-example-request"))
         (let [[txs query] @db]
           (t/is (= 2 (count @db)))
           (t/is (= [[:sql "INSERT INTO docs (_id, col1) VALUES (1, 'foo')"]          
                     [:sql "
INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'}"]]
                  txs))
           (t/is (= "SELECT * FROM docs"
                  query))
           (t/is (not (str/includes? (first txs) ";")))))

       (t/testing "beta sql example sends expected payload to xtdb"
         (clean-db)
         (h/run-handler (t-file "beta-sql-example-request"))
         (let [[txs query] @db]
           (t/is (= 2 (count @db)))
           (t/is (= ["INSERT INTO docs (_id, col1) VALUES (1, 'foo');
INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};"]
                  txs))
           (t/is (= ["SELECT * FROM docs"]
                  query))
           (t/is (str/includes? (first txs) ";")))))))

(t/deftest run-handler-multi-transactions-test
  (with-stubs
    #(do
       (t/testing "multiple transactions in xtql"
         (clean-db)
         (h/run-handler (assoc-in
                         (t-file "xtql-example-request")
                         [:parameters :body :tx-batches]
                         [{:txs "[:put-docs :docs {:xt/id 1 :foo \"bar\"}]",
                           :system-time "2024-12-01T00:00:00.000Z"}
                          {:txs "[:put-docs :docs {:xt/id 2 :foo \"baz\"}]",
                           :system-time nil}]))
         (let [[tx1 tx2 query] @db]
           (t/is (= 3 (count @db)))
           (t/is (= [[:put-docs :docs {:xt/id 1, :foo "bar"}]]
                    tx1))
           (t/is (= [[:put-docs :docs {:xt/id 2, :foo "baz"}]]
                    tx2))
           (t/is (= '(from :docs [xt/id foo])
                    query))))

       (t/testing "multiple transacions on sql"
         (clean-db)
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
               "SELECT *, _valid_from FROM docs")))
         (let [[tx1 tx2 query] @db]
           (t/is (= 3 (count @db)))
           (t/is (= [[:sql "INSERT INTO docs (_id, col1) VALUES (1, 'foo')"]]
                    tx1))
           (t/is (= [[:sql "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'}"]]
                    tx2))
           (t/is (= "SELECT *, _valid_from FROM docs"
                    query)))
         )

       (t/testing "multiple transacions on beta sql"
         (clean-db)
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
               "SELECT *, _valid_from FROM docs")))

         (t/is (= 7 (count @db)))
         (t/is (= [["BEGIN AT SYSTEM_TIME TIMESTAMP '2024-12-01T00:00:00.000Z'"]
                   ["INSERT INTO docs (_id, col1) VALUES (1, 'foo');"]
                   ["COMMIT"]
                   ["BEGIN AT SYSTEM_TIME TIMESTAMP '2024-12-02T00:00:00.000Z'"]
                   ["INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};"]
                   ["COMMIT"]
                   ["SELECT *, _valid_from FROM docs"]]
                  @db))))))

(t/deftest do-not-drop-columns
  (with-stubs
    #(do
       (t/testing "Bob still likes fishing - don't determine columns based on the first row"
         (mock-resp [{"_id" 2, "favorite_color" "red", "name" "carol"}
                     {"_id" 9, "likes" ["fishing" 3.14 {"nested" "data"}], "name" "bob"}])
         (t/is (= {:status 200,
                   :body
                   [["_id" "favorite_color" "name" "likes"]
                    [2 "red" "carol" nil]
                    [9 nil "bob" ["fishing" 3.14 {"nested" "data"}]]]}
                  (h/run-handler
                   (assoc-in
                    (t-file "sql-multi-transaction")
                    [:parameters :body :query]
                    "SELECT * FROM people")))))
       (reset-resp))))

(def docs-json
  "{\"tx-batches\":[{\"txs\":\"[[:sql \\\"INSERT INTO product (_id, name, price) VALUES\\\\n(1, 'An Electric Bicycle', 400)\\\"]]\",\"system-time\":\"2024-01-01\"},{\"txs\":\"[[:sql \\\"UPDATE product SET price = 405 WHERE _id = 1\\\"]]\",\"system-time\":\"2024-01-05\"},{\"txs\":\"[[:sql \\\"UPDATE product SET price = 350 WHERE _id = 1\\\"]]\",\"system-time\":\"2024-01-10\"}],\"query\":\"\\\"SELECT *, _valid_from\\\\nFROM product\\\\nFOR VALID_TIME ALL -- i.e. \\\\\\\"show me all versions\\\\\\\"\\\\nFOR SYSTEM_TIME AS OF DATE '2024-01-31' -- \\\\\\\"...as observed at month end\\\\\\\"\\\"\"}"
  )

(t/deftest docs-run
  (with-stubs
    #(do
       (clean-db)
       (mock-resp [{"_id" 1,
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
                    "_valid_from" #time/zoned-date-time "2024-01-01T00:00Z[UTC]"}])
       
       (let [response (h/docs-run-handler {:parameters {:body (json/read-str docs-json :key-fn keyword)}})
             txs (drop-last @db)
             query (last @db)]

         (t/is (= [[[:sql "INSERT INTO product (_id, name, price) VALUES\n(1, 'An Electric Bicycle', 400)"]]
                   [[:sql "UPDATE product SET price = 405 WHERE _id = 1"]]
                   [[:sql "UPDATE product SET price = 350 WHERE _id = 1"]]]
                  txs))

         (t/is (= "SELECT *, _valid_from\nFROM product\nFOR VALID_TIME ALL -- i.e. \"show me all versions\"\nFOR SYSTEM_TIME AS OF DATE '2024-01-31' -- \"...as observed at month end\""
                  query))
         
         (t/testing "docs run returns map results"
           (t/is (every? map? (:body response))))

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
                    response)))))))
