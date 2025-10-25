(ns xt-play.app
  (:require [day8.re-frame.http-fx] ;; don't delete
            [goog.events :as events]
            [goog.events.EventType :as EventType]
            [lambdaisland.glogi :as log]
            [lambdaisland.glogi.console :as glogi-console]
            [re-frame.core :as rf]
            ["react-dom/client" :refer [createRoot]]
            [reagent.core :as r]
            [goog.dom :as gdom]
            [xt-play.components.highlight :as hl]
            [xt-play.model.query-params :as query-params]
            [xt-play.model.run :as run]
            [xt-play.model.tx-batch :as tx-batch]
            [xt-play.view :as view]))

(glogi-console/install!)

(log/set-levels
 {:glogi/root :info}) ;; Set a root logger level, this will be inherited by all loggers

;; 'my.app.thing :trace  ;; Some namespaces you might want detailed logging

;; TODO: Special case existing txs
(defn- param-decode [s enc]
  (let [txs (-> (query-params/decode-from-binary s enc)
                js/JSON.parse
                (js->clj :keywordize-keys true))]
    (->> txs
         (mapv #(update % :system-time (fn [d] (when d (js/Date. d))))))))

(rf/reg-event-fx
 ::init
 [(rf/inject-cofx ::query-params/get)]
 (fn [{:keys [query-params]} [_ xt-version]]
   ;; keep query here - this is so that any old URLs/doc links keep working - just put query w/txs (statements)
   (let [{:keys [type txs query enc]} query-params
         ;; in case of saved link with sql-beta - translate to sql-v2
         type (keyword
               (if (or (empty? type)
                       (#{"sql-beta" "sql"} type))
                 "sql-v2"
                 type))
         txs (if txs
               (param-decode txs enc)
               [(tx-batch/default type)
                (tx-batch/default-query type)])
         statements (if query
                      (conj txs {:txs (query-params/decode-from-binary query enc)
                                 :query "true"})
                      txs)]
     {:db {:version xt-version
           :type type
           :enc enc}
      :dispatch [::tx-batch/init
                 statements]})))

(defonce root (createRoot (gdom/getElement "app")))

(defn handle-keypress [evt]
  (let [key (.-key evt)]
    (when (and (or (.-ctrlKey evt)
                   (.-metaKey evt))
               (= "Enter" key))
      ;; Prevent default behavior (inserting newline) and stop propagation
      (.preventDefault evt)
      (.stopPropagation evt)
      ;; Dispatch the update-and-run event which updates editors then runs
      (rf/dispatch [::view/update-and-run view/global-tx-refs]))))

(defn ^:dev/after-load start! []
  (log/info :start "start")
  (hl/setup)
  (rf/dispatch-sync [::init js/xt_version])
  ;; Use capture phase (true) to intercept Ctrl+Enter before CodeMirror handles it
  (events/listen js/document EventType/KEYDOWN handle-keypress true)
  (.render root (r/as-element [view/app])))

(defn ^:export init []
  ;; init is called ONCE when the page loads
  ;; this is called in the index.html and must be exported
  ;; so it is available even in :advanced release builds
  (log/info :init "init")
  (start!))

;; this is called before any code is reloaded
(defn ^:dev/before-load stop []
  (log/info :stop "stop"))
