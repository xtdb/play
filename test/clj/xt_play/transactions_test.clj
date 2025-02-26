(ns xt-play.transactions-test
  (:require [clojure.test :refer [deftest is testing]]
            [xt-play.transactions :as txs]))

(deftest test-split-sql
  (testing "-- comments"
    (is (= ["SELECT * FROM docs"]
           (txs/split-sql "SELECT * FROM docs; -- comment")))
    (is (= ["SELECT * FROM docs"]
           (txs/split-sql "-- ' \n SELECT * FROM docs;")))
    (is (= ["SELECT * FROM docs"]
           (txs/split-sql "-- /*  \n SELECT * FROM docs;"))))

  (testing "/* comment */"
    (is (= ["SELECT * FROM docs"]
           (txs/split-sql "/* ' */ SELECT * FROM docs; ")))
    (is (= ["SELECT * FROM docs"]
           (txs/split-sql " SELECT * /* -- comment */FROM docs; ")))
    (is (= ["SELECT * FROM docs"]
           (txs/split-sql "/* this \nis  \n  comment*/ SELECT * FROM docs; ")))))
