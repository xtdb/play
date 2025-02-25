(ns xt-play.model.run
  (:require [ajax.core :as ajax]
            [re-frame.core :as rf]
            [xt-play.model.tx-batch :as tx-batch]))

(def timeout-millis 30000)

(defn- db-run-opts [{:keys [type] :as db}]
  (let [params {:tx-type type
                :tx-batches (map #(update % :system-time (fn [d] (when d (.toISOString d))))
                                 (tx-batch/batch-list db))}]
    {:method :post
     :uri "/beta-db-run"
     :params params
     :timeout timeout-millis ;; timeout must be greater than the API cold start response time
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
 (fn [db [_ {:keys [failure status-text response] :as _failure-map}]]
   (js/console.log "Request failed: " (clj->js failure))
   (-> db
       (dissoc ::loading?)
       (assoc ::failure (if (= :timeout failure)
                          {:message status-text
                           :data (str "Request timed out after "
                                      (/ timeout-millis 1000) " seconds")}
                          response)
              ::show-results? true))))

(rf/reg-event-db
 ::reset-results
 (fn [db _]
   (dissoc db ::results ::failure ::response?)))

(rf/reg-event-db
 ::delete-result
 (fn [db [_ position]]
   (let [results-wo-deleted (vec (remove nil? (assoc (::results db) position nil)))]
     (assoc db ::results results-wo-deleted))))

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
