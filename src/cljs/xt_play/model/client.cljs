(ns xt-play.model.client
  (:require [re-frame.core :as rf]
            [xt-play.model.clipboard :as clipboard]
            [xt-play.model.href :as href]
            [xt-play.model.query-params :as query-params]
            [xt-play.model.tx-batch :as tx-batch]))

(rf/reg-event-db
 :hide-copy-tick
 (fn [db _]
   (dissoc db :copy-tick)))

(rf/reg-event-fx
 :copy-url
 [(rf/inject-cofx ::href/get)]
 (fn [{:keys [db href]} _]
   {::clipboard/set {:text href}
    :db (assoc db :copy-tick true)
    :dispatch-later {:ms 800 :dispatch [:hide-copy-tick]}}))

(rf/reg-sub
 :copy-tick
 :-> :copy-tick)

(defn- param-encode [tx-batches]
  (-> tx-batches clj->js js/JSON.stringify query-params/encode-to-binary))

(rf/reg-event-fx
 :update-url
 (fn [{:keys [db]} _]
   {::query-params/set {:version (:version db)
                        :type "sql-v2"
                        :enc 2
                        :txs (param-encode (tx-batch/batch-list db))}}))

(rf/reg-event-fx
 :fx ;; todo, use explicit reg-event-fxs
 (fn [_ [_ effects]]
   {:fx effects}))

(rf/reg-sub
 :version
 :-> :version)
