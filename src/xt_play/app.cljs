(ns xt-play.app
  (:require [lambdaisland.glogi :as log]
            [lambdaisland.glogi.console :as glogi-console]
            [re-frame.core :as rf]
            [reagent.dom :as r-dom]
            [xt-play.query-params :as query-params]
            [xt-play.highlight :as hl]
            [xt-play.tx-batch :as tx-batch]
            [xt-play.query :as query]
            [xt-play.client :as client]
            [day8.re-frame.http-fx]))

(glogi-console/install!)

(log/set-levels
 {:glogi/root   :info})    ;; Set a root logger level, this will be inherited by all loggers
  ;; 'my.app.thing :trace  ;; Some namespaces you might want detailed logging

(rf/reg-event-fx
  ::init
  [(rf/inject-cofx ::query-params/get)]
  (fn [{:keys [query-params]} [_ xt-version]]
    (let [{:keys [type txs query]} query-params
          type (if type (keyword type) :sql)]
      {:db {:version xt-version
            :type type
            :query (if query
                     (js/atob query)
                     (query/default type))}
       :dispatch [::tx-batch/init
                  (if txs
                    (tx-batch/param-decode txs)
                    [(tx-batch/default type)])]})))

(defn ^:dev/after-load start! []
  (log/info :start "start")
  (hl/setup)
  (rf/dispatch-sync [::init js/xt_version])
  (r-dom/render [client/app] (js/document.getElementById "app")))

(defn ^:export init []
  ;; init is called ONCE when the page loads
  ;; this is called in the index.html and must be exported
  ;; so it is available even in :advanced release builds
  (log/info :init "init")
  (start!))

;; this is called before any code is reloaded
(defn ^:dev/before-load stop []
  (log/info :stop "stop"))
