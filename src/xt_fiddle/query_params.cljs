(ns xt-fiddle.query-params
  (:require [re-frame.core :as rf]))

(defn get-query-params []
  (->> (js/URLSearchParams. (.-search js/window.location))
       (map js->clj)
       (into {})))

(rf/reg-cofx ::get
  (fn [cofx _]
    (assoc cofx :query-params (get-query-params))))

(defn set-query-params [params]
  (let [search-params (js/URLSearchParams.)]
    (doseq [[k v] params]
      (.set search-params (name k) (str v)))
    (set! (.-search js/window.location) (.toString search-params))))

(rf/reg-fx ::set set-query-params)
