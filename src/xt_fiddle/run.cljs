(ns xt-fiddle.run
  (:require [xt-fiddle.tx-batch :as tx-batch]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [ajax.core :as ajax]))

(defn encode-txs [txs type]
  (case type
    :sql (->> (str/split txs #";")
              (remove str/blank?)
              (map #(do [:sql %]))
              (vec)
              (str))
    :xtql (str "[" txs "]")))

(defn remove-last-semicolon [s]
  (str/replace s #";\s*$" ""))

(defn encode-query [query type]
  (case type
    :sql (-> query remove-last-semicolon pr-str)
    :xtql query))

(rf/reg-event-fx ::run
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc ::loading? true)
             (dissoc ::failure ::results))
     :http-xhrio {:method :post
                  :uri "/db-run"
                  :params {:tx-batches
                           (->> (tx-batch/list db)
                                (map #(update % :txs encode-txs (:type db)))
                                (map #(update % :system-time (fn [d] (when d (.toISOString d))))))
                           :query (encode-query (:query db) (:type db))}
                  :timeout 3000
                  :format (ajax/json-request-format)
                  :response-format (ajax/json-response-format {:keywords? true})
                  :on-success [::request-success]
                  :on-failure [::request-failure]}}))

(rf/reg-event-db ::request-success
  (fn [db [_ results]]
    (-> db
        (dissoc ::loading?)
        (assoc ::results results))))

(rf/reg-event-db ::request-failure
  (fn [db [_ {:keys [response] :as _failure-map}]]
    (-> db
        (dissoc ::loading?)
        (assoc ::failure response))))

(rf/reg-sub ::results-or-failure
  :-> #(let [results (select-keys % [::results ::failure])]
         (when-not (empty? results)
           results)))

(rf/reg-sub ::results?
  :<- [::results-or-failure]
  :-> boolean)

(rf/reg-sub ::loading?
  :-> ::loading?)
