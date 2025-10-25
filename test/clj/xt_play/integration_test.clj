(ns ^:integration xt-play.integration-test
  (:require [clojure.edn :as edn]
            [clojure.test :as t]
            [clojure.data.json :as json]
            #_:clj-kondo/ignore
            [time-literals.data-readers :as time]
            [xt-play.handler :as h]))

;; todo - this should be purely testing xt responses, not the handler.
;; can share the expected tx data from the results to keep in sync with changes

(defn- t-file [path]
  (edn/read-string (slurp (format "test-resources/%s.edn" path))))

(t/deftest run-handler-test
  (t/testing "xtql example returns expected results"
    (t/is (= {:status 200, :body [[[]] [[[:foo :xt/id] ["bar" 1]]]]}
             (h/run-handler (t-file "xtql-example-request")))))

  (t/testing "sql example returns expected results"
    (let [result (h/run-handler (t-file "sql-example-request"))]
      ;; Check status and structure, but allow tx-id and system-time to vary
      (t/is (= 200 (:status result)))
      (t/is (= 2 (count (:body result))))
      ;; First batch has 2 DML statements
      (t/is (= 2 (count (first (:body result)))))
      ;; Each DML returns tx-id and system-time
      (t/is (= ["tx-id" "system-time"] (ffirst (first (:body result)))))
      ;; Second batch is the query result
      (t/is (= [[:xt/id :col1 :col2]
                [2 "bar" " baz"]
                [1 "foo" nil]]
               (first (second (:body result)))))))

  (t/testing "beta sql example returns expected results"
    (let [result (h/run-handler (t-file "beta-sql-example-request"))]
      ;; Check status and structure, but allow tx-id and system-time to vary
      (t/is (= 200 (:status result)))
      (t/is (= 2 (count (:body result))))
      ;; First batch has 2 DML statements
      (t/is (= 2 (count (first (:body result)))))
      ;; Each DML returns tx-id and system-time
      (t/is (= ["tx-id" "system-time"] (ffirst (first (:body result)))))
      ;; Second batch is the query result
      (t/is (= [[:xt/id :col1 :col2]
                [2 "bar" " baz"]
                [1 "foo" nil]]
               (first (second (:body result))))))))

(t/deftest run-handler-multi-transactions-test
  (t/testing "multiple transactions in xtql"
    (t/is (= {:status 200, :body [[[]] [[]]
                                  [[[:foo :xt/id]
                                    ["baz" 2]
                                    ["bar" 1]]]]}
             (h/run-handler
              (assoc-in
               (t-file "xtql-example-request")
               [:parameters :body :tx-batches]
               [{:txs "[:put-docs :docs {:xt/id 1 :foo \"bar\"}]",
                 :system-time "2024-12-01T00:00:00.000Z"}
                {:txs "[:put-docs :docs {:xt/id 2 :foo \"baz\"}]",
                 :system-time nil}
                {:txs "(from :docs [xt/id foo])", :query true}])))))

  (t/testing "multiple transacions on sql"
    (let [result (h/run-handler
                  (-> (t-file "sql-example-request")
                      (assoc-in
                       [:parameters :body :tx-batches]
                       [{:txs "INSERT INTO docs (_id, col1) VALUES (1, 'foo');",
                         :system-time "2024-12-01T00:00:00.000Z"}
                        {:txs "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};",
                         :system-time "2024-12-02T00:00:00.000Z"}
                        {:txs "SELECT *, _valid_from FROM docs" :query true}])))]
      (t/is (= 200 (:status result)))
      (t/is (= 3 (count (:body result))))
      ;; First two batches are DML - extract :result from each
      (t/is (= ["tx-id" "system-time"] (first (:result (first (first (:body result)))))))
      (t/is (= ["tx-id" "system-time"] (first (:result (first (second (:body result)))))))
      ;; Third batch is the query - also has :result wrapper
      (let [query-result (:result (first (nth (:body result) 2)))]
        (t/is (= [["_id" "col1" "col2" "_valid_from"]
                  [2 "bar" " baz" "TIMESTAMP '2024-12-02T00:00Z[UTC]'"]
                  [1 "foo" nil "TIMESTAMP '2024-12-01T00:00Z[UTC]'"]]
                 query-result))))))

(t/testing "beta sql can run multiple txs"
  (let [result (h/run-handler
                (-> (t-file "beta-sql-example-request")
                    (assoc-in
                     [:parameters :body :tx-batches]
                     [{:txs "INSERT INTO docs (_id, col1) VALUES (1, 'foo');",
                       :system-time "2024-12-01T00:00:00.000Z"}
                      {:txs "INSERT INTO docs RECORDS {_id: 2, col1: 'bar', col2:' baz'};",
                       :system-time "2024-12-02T00:00:00.000Z"}
                      {:txs "SELECT *, _valid_from FROM docs" :query true}])))]
    (t/is (= 200 (:status result)))
    (t/is (= 3 (count (:body result))))
      ;; First two batches are DML - extract :result from each
    (t/is (= ["tx-id" "system-time"] (first (:result (first (first (:body result)))))))
    (t/is (= ["tx-id" "system-time"] (first (:result (first (second (:body result)))))))
      ;; Third batch is the query - also has :result wrapper
    (let [query-result (:result (first (nth (:body result) 2)))]
      (t/is (= [["_id" "col1" "col2" "_valid_from"]
                [2 "bar" " baz" "TIMESTAMP '2024-12-02T00:00Z[UTC]'"]
                [1 "foo" nil "TIMESTAMP '2024-12-01T00:00Z[UTC]'"]]
               query-result)))))

(t/deftest beta-sql-run-features
  (t/testing "Column order is maintained"
    (let [result (h/run-handler
                  (assoc-in
                   (t-file "beta-sql-example-request")
                   [:parameters :body :tx-batches]
                   [{:txs "INSERT INTO docs RECORDS {_id: 1, a: 2, b: 3, c: 4, d: 5, e: 6, f: 7, g: 8, h: 9, j: 10}"
                     :system-time nil}
                    {:txs "SELECT * FROM docs" :query true}]))]
      (t/is (= 200 (:status result)))
      (t/is (= 2 (count (:body result))))
      ;; First batch is DML
      (t/is (= ["tx-id" "system-time"] (ffirst (first (:body result)))))
      ;; Second batch is the query with column order maintained
      (t/is (= [[:e :g :c :j :h :b :d :f :xt/id :a]
                [6 8 4 10 9 3 5 7 1 2]]
               (first (second (:body result))))))))

(t/deftest begin-commit-with-system-time
  (t/testing "BEGIN with SYSTEM_TIME override groups statements into single transaction"
    (let [result (h/run-handler
                  {:parameters
                   {:body
                    {:tx-type "sql-v2",
                     :tx-batches [{:txs "BEGIN READ WRITE WITH (SYSTEM_TIME = TIMESTAMP '2024-01-15T10:00:00.000Z');
                                         INSERT INTO products (_id, name, price) VALUES (1, 'Widget', 100);
                                         INSERT INTO products (_id, name, price) VALUES (2, 'Gadget', 200);
                                         COMMIT;"}]}}})]
      (t/is (= 200 (:status result)))
      ;; Should get one transaction result (all statements grouped together)
      (t/is (= 1 (count (:body result))))
      ;; The result should show tx-id and system-time
      (let [tx-result (first (first (:body result)))]
        (t/is (contains? tx-result :result))
        (t/is (= ["tx-id" "system-time"] (first (:result tx-result))))
        ;; Verify system time was applied (should contain the timestamp we specified)
        (t/is (clojure.string/includes? (str (second (:result tx-result))) "2024-01-15")))))

  (t/testing "Multiple BEGIN/COMMIT blocks create separate transactions"
    (let [result (h/run-handler
                  {:parameters
                   {:body
                    {:tx-type "sql-v2",
                     :tx-batches [{:txs "BEGIN READ WRITE WITH (SYSTEM_TIME = TIMESTAMP '2024-01-01');
                                         INSERT INTO orders (_id, product) VALUES (1, 'A');
                                         COMMIT;
                                         BEGIN READ WRITE WITH (SYSTEM_TIME = TIMESTAMP '2024-01-02');
                                         INSERT INTO orders (_id, product) VALUES (2, 'B');
                                         COMMIT;
                                         SELECT * FROM orders;"}]}}})]
      (t/is (= 200 (:status result)))
      ;; Should get 3 results: tx1, tx2, query
      (t/is (= 3 (count (first (:body result)))))
      ;; First two should be transaction results
      (t/is (= ["tx-id" "system-time"] (first (:result (first (first (:body result)))))))
      (t/is (= ["tx-id" "system-time"] (first (:result (second (first (:body result)))))))
      ;; Third should be query result
      (let [query-result (:result (nth (first (:body result)) 2))]
        (t/is (= ["_id" "product"] (first query-result)))
        (t/is (= 2 (count (rest query-result)))))))

  (t/testing "Statements without BEGIN/COMMIT still work"
    (let [result (h/run-handler
                  {:parameters
                   {:body
                    {:tx-type "sql-v2",
                     :tx-batches [{:txs "INSERT INTO simple (_id) VALUES (99);
                                         SELECT * FROM simple;"}]}}})]
      (t/is (= 200 (:status result)))
      ;; Should get 2 results: one tx, one query
      (t/is (= 2 (count (first (:body result))))))))

(t/testing "erroneous statement returns error in v2"
  (let [result (h/run-handler
                {:parameters
                 {:body
                  {:tx-type "sql-v2",
                   :tx-batches [{:txs "SELECT (PERIOD(DATE '2024-01-01', DATE '2024-01-04') + INTERVAL '1' MINUTE)"
                                 :query true}]}}})]
    (t/is (= 200 (:status result)))
      ;; Check that we got an error response - error is first element of first batch
    (let [first-batch (first (:body result))
          first-item (first first-batch)]
      (t/is (contains? first-item :error)))))

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
                             "sql-v2")))))

  (t/testing "Bob still likes fishing - don't determine columns based on the first row"
    (t/is (= {:status 200,
              :body
              [[:_id :favorite_color :info :likes :name]
               [2 "red" nil nil "carol"]
               [9 nil nil ["fishing" 3.14 {:nested "data"}] "bob"]]}
             (h/run-handler
              (-> (t-file "sql-multi-transaction")
                  (assoc-in [:parameters :body :query] "SELECT * FROM people")
                  (assoc-in [:parameters :body :tx-type] "sql-v2")))))))

(def docs-json
  "{\"tx-batches\":[{\"txs\":\"[[:sql \\\"INSERT INTO product (_id, name, price) VALUES\\\\n(1, 'An Electric Bicycle', 400)\\\"]]\",\"system-time\":\"2024-01-01\"},{\"txs\":\"[[:sql \\\"UPDATE product SET price = 405 WHERE _id = 1\\\"]]\",\"system-time\":\"2024-01-05\"},{\"txs\":\"[[:sql \\\"UPDATE product SET price = 350 WHERE _id = 1\\\"]]\",\"system-time\":\"2024-01-10\"}],\"query\":\"\\\"SELECT *, _valid_from\\\\nFROM product\\\\nFOR VALID_TIME ALL -- i.e. \\\\\\\"show me all versions\\\\\\\"\\\\nFOR SYSTEM_TIME AS OF DATE '2024-01-31' -- \\\\\\\"...as observed at month end\\\\\\\"\\\"\"}")

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
