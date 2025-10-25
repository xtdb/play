(ns xt-play.handler-test
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.test :as t]
   [xt-play.handler :as h]
   #_:clj-kondo/ignore
   [time-literals.data-readers :as time]
   [xt-play.xtdb-stubs :refer [clean-db db mock-resp reset-resp with-stubs]]))
;; todo:
;; [ ] test unhappy paths
;; [ ] test wider range of scenarios / formats
;; [x] test to pipeline
;; [ ] assert format from client
;; [x] stub xtdb
;; [x] integration tests with xtdb

(defn- t-file [path]
  (edn/read-string (slurp (format "test-resources/%s.edn" path))))

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
         (let [[tx1 tx2 query] @db]
           (t/is (= 3 (count @db)))
           ;; DML now uses xt/execute-tx with [:sql statement] format
           (t/is (= [[:sql "INSERT INTO docs (_id, col1) VALUES (1, 'foo')"]]
                    (:tx-ops tx1)))
           (t/is (= [[:sql "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'}"]]
                    (:tx-ops tx2)))
           ;; Queries use xt/q with raw SQL strings
           (t/is (= "SELECT * FROM docs"
                    query))))

       (t/testing "beta sql example sends expected payload to xtdb"
         (clean-db)
         (h/run-handler (t-file "beta-sql-example-request"))
         (let [[tx1 tx2 query] @db]
           (t/is (= 3 (count @db)))
           ;; DML now uses xt/execute-tx with [:sql statement] format
           (t/is (= [[:sql "INSERT INTO docs (_id, col1) VALUES (1, 'foo')"]] (:tx-ops tx1)))
           (t/is (= [[:sql "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'}"]] (:tx-ops tx2)))
           ;; Queries use xt/q with raw SQL strings
           (t/is (= "SELECT * FROM docs" query)))))))

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
         (let [[tx1 tx2] @db]
           (t/is (= 2 (count @db)))
           (t/is (= [[:put-docs :docs {:xt/id 1, :foo "bar"}]]
                    tx1))
           (t/is (= [[:put-docs :docs {:xt/id 2, :foo "baz"}]]
                    tx2))))

       (t/testing "multiple transacions on sql"
         (clean-db)
         (h/run-handler
          (-> (t-file "sql-example-request")
              (assoc-in
               [:parameters :body :tx-batches]
               [{:txs "INSERT INTO docs (_id, col1) VALUES (1, 'foo');",
                 :system-time "2024-12-01T00:00:00.000Z"}
                {:txs "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};",
                 :system-time "2024-12-02T00:00:00.000Z"}
                {:txs "SELECT * FROM docs" :query true}])))
         ;; BEGIN/COMMIT are filtered out and used for grouping - only DML/queries are sent
         (let [[tx1 tx2 query] @db]
           (t/is (= 3 (count @db)))
           (t/is (= [[:sql "INSERT INTO docs (_id, col1) VALUES (1, 'foo')"]]
                    (:tx-ops tx1)))
           ;; Verify system time from batch-level :system-time is applied
           (t/is (= #inst "2024-12-01T00:00:00.000-00:00"
                    (:system-time (:opts tx1))))
           (t/is (= [[:sql "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'}"]]
                    (:tx-ops tx2)))
           (t/is (= #inst "2024-12-02T00:00:00.000-00:00"
                    (:system-time (:opts tx2))))
           (t/is (= "SELECT * FROM docs"
                    query))))

       (t/testing "multiple transacions on beta sql"
         (clean-db)
         (h/run-handler
          (-> (t-file "beta-sql-example-request")
              (assoc-in
               [:parameters :body :tx-batches]
               [{:txs "INSERT INTO docs (_id, col1) VALUES (1, 'foo');",
                 :system-time "2024-12-01T00:00:00.000Z"}
                {:txs "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};",
                 :system-time "2024-12-02T00:00:00.000Z"}
                {:txs "SELECT *, _valid_from FROM docs" :query true}])))
         ;; BEGIN/COMMIT are filtered out and used for grouping - only DML/queries are sent
         (let [[tx1 tx2 query] @db]
           (t/is (= 3 (count @db)))
           (t/is (= [[:sql "INSERT INTO docs (_id, col1) VALUES (1, 'foo')"]]
                    (:tx-ops tx1)))
           ;; Verify system time from batch-level :system-time is applied
           (t/is (= #inst "2024-12-01T00:00:00.000-00:00"
                    (:system-time (:opts tx1))))
           (t/is (= [[:sql "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'}"]]
                    (:tx-ops tx2)))
           (t/is (= #inst "2024-12-02T00:00:00.000-00:00"
                    (:system-time (:opts tx2))))
           (t/is (= "SELECT *, _valid_from FROM docs"
                    query)))))))

(t/deftest begin-commit-transaction-grouping-test
  (with-stubs
    #(do
       (t/testing "BEGIN/COMMIT groups statements and extracts system time"
         (clean-db)
         (h/run-handler
          {:parameters
           {:body
            {:tx-type "sql-v2",
             :tx-batches [{:txs "BEGIN READ WRITE WITH (SYSTEM_TIME = TIMESTAMP '2024-01-15T10:00:00.000Z');
                                 INSERT INTO products (_id, name) VALUES (1, 'Widget');
                                 INSERT INTO products (_id, name) VALUES (2, 'Gadget');
                                 COMMIT;"}]}}})
         ;; Should get a single transaction with both INSERT statements
         (let [[tx] @db]
           (t/is (= 1 (count @db)))
           ;; Both INSERTs should be in one transaction (BEGIN/COMMIT filtered out)
           (t/is (= [[:sql "INSERT INTO products (_id, name) VALUES (1, 'Widget')"]
                     [:sql "INSERT INTO products (_id, name) VALUES (2, 'Gadget')"]]
                    (:tx-ops tx)))
           ;; System time from BEGIN should be applied
           (t/is (= #inst "2024-01-15T10:00:00.000-00:00"
                    (:system-time (:opts tx))))))

       (t/testing "Multiple BEGIN/COMMIT blocks create separate transactions"
         (clean-db)
         (h/run-handler
          {:parameters
           {:body
            {:tx-type "sql-v2",
             :tx-batches [{:txs "BEGIN READ WRITE WITH (SYSTEM_TIME = TIMESTAMP '2024-01-01');
                                 INSERT INTO orders (_id) VALUES (1);
                                 COMMIT;
                                 BEGIN READ WRITE WITH (SYSTEM_TIME = TIMESTAMP '2024-01-02');
                                 INSERT INTO orders (_id) VALUES (2);
                                 COMMIT;"}]}}})
         ;; Should get two separate transactions
         (let [[tx1 tx2] @db]
           (t/is (= 2 (count @db)))
           (t/is (= [[:sql "INSERT INTO orders (_id) VALUES (1)"]]
                    (:tx-ops tx1)))
           (t/is (= [[:sql "INSERT INTO orders (_id) VALUES (2)"]]
                    (:tx-ops tx2)))
           ;; Verify system times from each BEGIN
           (t/is (= #inst "2024-01-01T00:00:00.000-00:00"
                    (:system-time (:opts tx1))))
           (t/is (= #inst "2024-01-02T00:00:00.000-00:00"
                    (:system-time (:opts tx2))))))

       (t/testing "Statements without BEGIN/COMMIT are treated individually"
         (clean-db)
         (h/run-handler
          {:parameters
           {:body
            {:tx-type "sql-v2",
             :tx-batches [{:txs "INSERT INTO simple (_id) VALUES (1);
                                 INSERT INTO simple (_id) VALUES (2);"}]}}})
         ;; Without BEGIN/COMMIT, each statement is separate
         (let [[tx1 tx2] @db]
           (t/is (= 2 (count @db)))
           (t/is (= [[:sql "INSERT INTO simple (_id) VALUES (1)"]]
                    (:tx-ops tx1)))
           (t/is (= [[:sql "INSERT INTO simple (_id) VALUES (2)"]]
                    (:tx-ops tx2))))))))

;; mocking here really stuffs it up now that TX responses are also returned
#_(t/deftest do-not-drop-columns
    (with-stubs
      #(do
         (t/testing "Bob still likes fishing - don't determine columns based on the first row"
           (mock-resp [[["_id" 2] ["favorite_color" "red"] ["name" "carol"]]
                       [["_id" 9]
                        ["likes" ["fishing" 3.14 {"nested" "data"}]]
                        ["name" "bob"]]])
           (let [request (t-file "sql-multi-transaction")
                 num-transactions (-> request :parameters :body :tx-batches count)
                 response (h/run-handler
                           (update-in
                            request
                            [:parameters :body :tx-batches]
                            conj
                            {:txs "SELECT * FROM people" :query true}))]
             (t/is (= {:status 200,
                       :body
                       (conj
                        (into []
                              (repeat
                               num-transactions
                               [[["_id" 2] ["favorite_color" "red"] ["name" "carol"]]
                                [["_id" 9]
                                 ["likes" ["fishing" 3.14 {"nested" "data"}]]
                                 ["name" "bob"]]
                                [["_id" 2] ["favorite_color" "red"] ["name" "carol"]]
                                [["_id" 9]
                                 ["likes" ["fishing" 3.14 {"nested" "data"}]]
                                 ["name" "bob"]]
                                [["_id" 2] ["favorite_color" "red"] ["name" "carol"]]
                                [["_id" 9]
                                 ["likes" ["fishing" 3.14 {"nested" "data"}]]
                                 ["name" "bob"]]]))
                        [[["_id" 2] ["favorite_color" "red"] ["name" "carol"]]
                         [["_id" 9]
                          ["likes" ["fishing" 3.14 {"nested" "data"}]]
                          ["name" "bob"]]])}
                      response))))
         (reset-resp))))

(def docs-json
  "{\"tx-batches\":[{\"txs\":\"[[:sql \\\"INSERT INTO product (_id, name, price) VALUES\\\\n(1, 'An Electric Bicycle', 400)\\\"]]\",\"system-time\":\"2024-01-01\"},{\"txs\":\"[[:sql \\\"UPDATE product SET price = 405 WHERE _id = 1\\\"]]\",\"system-time\":\"2024-01-05\"},{\"txs\":\"[[:sql \\\"UPDATE product SET price = 350 WHERE _id = 1\\\"]]\",\"system-time\":\"2024-01-10\"}],\"query\":\"\\\"SELECT *, _valid_from\\\\nFROM product\\\\nFOR VALID_TIME ALL -- i.e. \\\\\\\"show me all versions\\\\\\\"\\\\nFOR SYSTEM_TIME AS OF DATE '2024-01-31' -- \\\\\\\"...as observed at month end\\\\\\\"\\\"\"}")

(t/deftest docs-run
  (with-stubs
    #(do
       (clean-db)
       (reset-resp)
       (mock-resp [{:xt/id 1,
                    :name "An Electric Bicycle",
                    :price 350,
                    :xt/valid_from #time/zoned-date-time "2024-01-10T00:00Z"}
                   {:xt/id 1,
                    :name "An Electric Bicycle",
                    :price 405,
                    :xt/valid_from #time/zoned-date-time "2024-01-05T00:00Z"}
                   {:xt/id 1,
                    :name "An Electric Bicycle",
                    :price 400,
                    :xt/valid_from #time/zoned-date-time "2024-01-01T00:00Z"}])

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
           (t/is (every? map? (:result (:body response)))))

         (t/testing "can handle \" strings from docs"
           (t/is (= {:status 200,
                     :body
                     [{:xt/id 1,
                       :name "An Electric Bicycle",
                       :price 350,
                       :xt/valid_from #time/zoned-date-time "2024-01-10T00:00Z"}
                      {:xt/id 1,
                       :name "An Electric Bicycle",
                       :price 405,
                       :xt/valid_from #time/zoned-date-time "2024-01-05T00:00Z"}
                      {:xt/id 1,
                       :name "An Electric Bicycle",
                       :price 400,
                       :xt/valid_from #time/zoned-date-time "2024-01-01T00:00Z"}]}
                    response))))
       (reset-resp))))
