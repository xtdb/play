(ns xt-play.model.run
  (:require [ajax.core :as ajax]
            [re-frame.core :as rf]
            [xt-play.model.tx-batch :as tx-batch]))

(defn- db-run-opts [{:keys [type] :as db}]
  (let [params {:tx-type type
                :tx-batches (map #(update % :system-time (fn [d] (when d (.toISOString d))))
                                 (tx-batch/batch-list db))}]
    {:method :post
     :uri "/beta-db-run"
     :params params
     :timeout 3000
     :format (ajax/json-request-format)
     :response-format (ajax/json-response-format {:keywords? true})
     :on-success [::request-success]
     :on-failure [::request-failure]}))

(defn run [db]
  {:db (-> db
           (assoc ::loading? true
                  :enc 2)
           (dissoc ::failure ::results ::response?))
   :http-xhrio (db-run-opts db)})

(rf/reg-event-fx
 ::run
 (fn [{:keys [db]}]
   (merge (run db)
          {:dispatch [:update-url]})))

(rf/reg-event-db
 ::request-success
 (fn [db [_ results]]
   (-> db
       (dissoc ::loading?)
       (assoc ::response? true
              ::results results
              ::show-results? true))))

(rf/reg-event-db
 ::request-failure
  (fn [db [_ {:keys [response] :as _failure-map}]]
    (-> db
        (dissoc ::loading?)
        (assoc ::failure response
               ::show-results? true))))

(rf/reg-event-db
 ::reset-results
 (fn [db _]
   (dissoc db ::results ::failure ::response?)))

(rf/reg-event-db
 ::hide-results!
 (fn [db _]
   (dissoc db ::show-results?)))

(rf/reg-sub
 ::results-or-failure
 (fn [db]
   (let [results (select-keys db [::results ::failure ::response?])]
     (when-not (empty? results)
       results))))

(rf/reg-sub
 ::results?
  :<- [::results-or-failure]
  :-> boolean)

(rf/reg-sub
 ::loading?
  :-> ::loading?)

(rf/reg-sub
 ::show-results?
  :-> ::show-results?)

(comment
  (require '[re-frame.db :as db])
  (def db @db/app-db)
  (keys db))
