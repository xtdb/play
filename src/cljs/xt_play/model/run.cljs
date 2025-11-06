(ns xt-play.model.run
  (:require [ajax.core :as ajax]
            [cognitect.transit :as transit]
            [re-frame.core :as rf]
            [xt-play.model.tx-batch :as tx-batch]))

(deftype TemporalValue [tag value]
  IPrintWithWriter
  (-pr-writer [_ writer _]
    (write-all writer "#xt/" tag " \"" value "\"")))

(deftype XtdbValue [tag value]
  IPrintWithWriter
  (-pr-writer [_ writer _]
    (write-all writer "#xt/" tag " " (pr-str value))))

(def timeout-millis 85000)

(def transit-read-handlers
  {"time/zoned-date-time" (transit/read-handler #(TemporalValue. "zdt" %))
   "time/offset-date-time" (transit/read-handler #(TemporalValue. "odt" %))
   "time/local-date-time" (transit/read-handler #(TemporalValue. "ldt" %))
   "time/local-date" (transit/read-handler #(TemporalValue. "ld" %))
   "time/instant" (transit/read-handler #(TemporalValue. "instant" %))
   "xtdb/tstz-range" (transit/read-handler #(XtdbValue. "tstz-range" (vec %)))
   "xtdb/interval" (transit/read-handler #(XtdbValue. "interval" (vec %)))
   "xtdb/clj-form" (transit/read-handler #(XtdbValue. "clj-form" %))
   "xtdb/byte-array" (transit/read-handler #(XtdbValue. "byte-array" (vec %)))
   "xtdb/path" (transit/read-handler #(XtdbValue. "path" %))})

(defn- db-run-opts [{:keys [type] :as db}]
  (let [params {:tx-type type
                :tx-batches (map #(update % :system-time (fn [d] (when d (.toISOString d))))
                                 (tx-batch/batch-list db))}]
    {:method :post
     :uri "/beta-db-run"
     :params params
     :timeout timeout-millis
     :format (ajax/transit-request-format)
     :response-format (ajax/transit-response-format {:handlers transit-read-handlers})
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
