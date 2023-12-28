(ns xt-fiddle.client
  (:require [cljs.reader]
            [lambdaisland.glogi :as log]
            [lambdaisland.glogi.console :as glogi-console]
            [re-frame.core :as rf]
            [reagent.dom]
            [reagent.core :as r]))

(glogi-console/install!)

(log/set-levels
 {:glogi/root   :info    ;; Set a root logger level, this will be inherited by all loggers
  ;; 'my.app.thing :trace   ;; Some namespaces you might want detailed logging
  })

(rf/reg-event-db
 :app/init
 (fn [_ _]
   {:type :xtql}))

(rf/reg-event-db
 :dropdown-selection
 (fn [db [_ selection]]
   (assoc db :type selection)))

(rf/reg-sub
 :selection
 (fn [db _]
   (:type db)))


(defn dropdown []
  (let [open? (r/atom false)]
    (fn []
      [:div {:class "relative inline-block text-left"}
       [:button {:type "button"
                 :class "inline-flex justify-center w-full px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 focus:outline-none"
                 :id "dropdownDefault"
                 :data-dropdown-toggle "dropdown"
                 :on-click #(swap! open? not)}
        (if (= :xtql @(rf/subscribe [:selection]))
          "XTQL"
          "SQL")
        [:svg {:class"w-4 h-4 ml-2"
               :xmlns "http://www.w3.org/2000/svg"
               :fill "none"
               :viewBox "0 0 24 24"
               :stroke "currentColor"
               :aria-hidden "true"}
         [:path
          {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M19 9l-7 7-7-7"}]]]

       (when @open?
         [:div {:class "z-10 w-44 bg-white rounded divide-y divide-gray-100 shadow dark:bg-gray-700" :id "dropdown"}
          [:ul {:class "py-1 text-sm text-gray-700 dark:text-gray-200" :aria-labelledby "dropdownDefault"}
           [:li
            [:a {:href "#" :class  "block px-4 py-2 hover:bg-gray-100 dark:hover:bg-gray-600 dark:hover:text-white"
                 :on-click (fn [event]
                             (.preventDefault event)
                             (rf/dispatch [:dropdown-selection :xtql]))}
             "XTQL"]]
           [:li
            [:a {:href "#" :class  "block px-4 py-2 hover:bg-gray-100 dark:hover:bg-gray-600 dark:hover:text-white"
                 :on-click (fn [event]
                             (.preventDefault event)
                             (rf/dispatch [:dropdown-selection :sql]))}
             "SQL"]]]])])))


(defn app []
  [:div {:class "flex flex-col h-screen"}
   [:header {:class "bg-gray-200 p-4 text-lg font-semibold shadow-md flex items-center justify-between" #_"bg-gray-200 p-4 text-lg font-semibold"}
    [:div {:class "flex items-center space-x-4"}

     [:h2 "XT fiddle"]
     [:button  {:class "bg-blue-500 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded"}
      "Run"]

     [dropdown]]]

   [:div {:class "flex flex-1 overflow-hidden"}
    [:aside {:class "w-64 bg-gray-100 p-4 overflow-auto"}
     "Side"]
    [:div {:class "flex flex-col flex-1 overflow-hidden"}
     [:section {:class "flex flex-1 overflow-auto p-4"}
      [:div {:class "flex-1 bg-white border mr-4 p-4"}
       "Content 1"]
      [:div {:class "flex-1 bg-white border p-4"}
       "Content 2"]]
     [:section {:class "flex-1 bg-white p-4 border-t border-gray-300" :style {:flex-grow 1} }
      "Bottom content"]]]])


;; start is called by init and after code reloading finishes
(defn ^:dev/after-load start! []
  (js/console.log "start")
  (rf/dispatch-sync [:app/init])
  (reagent.dom/render [app] (js/document.getElementById "app")))

(defn ^:export init []
  ;; init is called ONCE when the page loads
  ;; this is called in the index.html and must be exported
  ;; so it is available even in :advanced release builds
  (js/console.log "init")
  (start!))

;; this is called before any code is reloaded
(defn ^:dev/before-load stop []
  (js/console.log "stop"))
