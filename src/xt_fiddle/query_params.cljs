(ns xt-fiddle.query-params
  (:require [re-frame.core :as rf]))

(defn get-query-params []
  (->> (js/URLSearchParams. (.-search js/window.location))
       (map js->clj)
       (map (fn [[k v]] [(keyword k) v]))
       (into {})))

(rf/reg-cofx ::get
  (fn [cofx _]
    (assoc cofx :query-params (get-query-params))))

(defn ->query-string [params]
  (let [search-params (js/URLSearchParams.)]
    (doseq [[k v] params]
      (.set search-params (name k) (str v)))
    (.toString search-params)))

(defn set-query-params [params]
  (set! (.-search js/window.location) (->query-string params)))

(rf/reg-fx ::set set-query-params)
