(ns xt-fiddle.client
  (:require [clojure.string :as str]
            [ajax.core :as ajax]
            [day8.re-frame.http-fx]
            [lambdaisland.glogi :as log]
            [lambdaisland.glogi.console :as glogi-console]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.dom]
            [xt-fiddle.editor :as editor]
            ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/clojure" :as hljs-clojure]))

(glogi-console/install!)

(log/set-levels
 {:glogi/root   :info})    ;; Set a root logger level, this will be inherited by all loggers
  ;; 'my.app.thing :trace  ;; Some namespaces you might want detailed logging

(rf/reg-event-db
 :app/init
 (fn [_ _]
   {:type #_:xtql :sql}))

(rf/reg-event-db
 :dropdown-selection
 (fn [db [_ selection]]
   (assoc db :type selection)))

(rf/reg-event-db
 :set-txs
 (fn [db [_ txs]]
   (assoc db :txs txs)))

(rf/reg-event-fx
 :set-xtql-txs
 (fn [_ [_ txs]]
   {:dispatch [:set-txs (str "[" txs "]")]}))

(rf/reg-event-fx
 :set-sql-txs
 (fn [_ [_ txs]]
   {:dispatch [:set-txs (str "["
                             (str/join " "
                               (->> (str/split txs #";")
                                    (map str/trim)
                                    (map #(str [:sql %]))))
                             "]")]}))

(rf/reg-event-db
 :set-query
 (fn [db [_ query]]
   (assoc db :query query)))

(rf/reg-event-db
 :set-sql-query
 (fn [db [_ query]]
   (assoc db :query (pr-str query))))

(rf/reg-sub
 :get-type
 (fn [db _]
   (:type db)))

(rf/reg-event-db
 :success-results
 (fn [db [_ results]]
   (-> db
       (dissoc :show-twirly)
       (assoc :results results))))

(rf/reg-event-db
 :failure-results
 (fn [db [_ {:keys [response] :as _failure-map}]]
   (-> db
       (dissoc :show-twirly)
       (assoc :failure response))))

(rf/reg-event-fx
 :db-run
 (fn [{:keys [db]} _]
   (log/info :txs {:txs (:txs db)})
   {:db (-> db
            (assoc :show-twirly true)
            (dissoc :failure :results))
    :http-xhrio {:method :post
                 :uri "/db-run"
                 :params {:txs (:txs db)
                          :query (:query db)
                          :type "xtql"}
                 :timeout 3000
                 :format (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success [:success-results]
                 :on-failure [:failure-results]}}))

(rf/reg-sub
 :twirly?
 (fn [db _]
   (:show-twirly db)))

(rf/reg-sub
 :results-or-failure
 (fn [db _]
   (select-keys db [:results :failure])))

(defn dropdown []
  (r/with-let [open? (r/atom false)
               click-handler (fn [event]
                               (log/info :event "foo")
                               (let [dropdown-elem (js/document.querySelector "#language-dropdown")]
                                 (when (not (.contains dropdown-elem (.-target event)))
                                   (reset! open? false))))
               _ (js/window.addEventListener "click" click-handler)]
    [:div {:class "relative inline-block text-left"}
     [:button {:type "button"
               :class "inline-flex justify-center w-full px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 focus:outline-none"
               :id "language-dropdown"
               :data-dropdown-toggle "dropdown"
               :on-change #(reset! open? false)
               :on-click #(swap! open? not)}
      (if (= :xtql @(rf/subscribe [:get-type]))
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
       [:div {:class "z-10 absolute w-44 bg-white rounded divide-y divide-gray-100 shadow dark:bg-gray-700" :id "dropdown"}
        [:ul {:class "py-1 text-sm text-gray-700 dark:text-gray-200" :aria-labelledby "dropdownDefault"}
         [:li
          [:a {:href "#" :class  "block px-4 py-2 hover:bg-gray-100 dark:hover:bg-gray-600 dark:hover:text-white"
               :on-click (fn [event]
                           (.preventDefault event)
                           (rf/dispatch [:dropdown-selection :xtql])
                           (reset! open? false))}
           "XTQL"]]
         ;; TODO actually implement editor
         [:li
          [:a {:href "#" :class  "block px-4 py-2 hover:bg-gray-100 dark:hover:bg-gray-600 dark:hover:text-white"
               :on-click (fn [event]
                           (.preventDefault event)
                           (rf/dispatch [:dropdown-selection :sql])
                           (reset! open? false))}
           "SQL"]]]])]
    (finally
      (js/window.removeEventListener "click" click-handler))))

(defn render-raw-html [html-content]
  [:div {:dangerouslySetInnerHTML {:__html html-content}}])

(defn highlight-code [code language]
  [render-raw-html (.-value (hljs/highlight code #js {:language language}))])

(defn spinner []
  [:div
   "Loading..."])

(defn display-error [{:keys [exception message data]}]
  [:div {:class "flex flex-col gap-2"}
   (when (= "xtdb.sql/parse-error" (:xtdb.error/error-key data))
     [:div {:class "bg-blue-100 border-l-4 border-blue-500 text-blue-700 p-4"}
      "Are you missing a ';' between statements?"])
   [:div {:class "bg-red-100 border-l-4 border-red-500 text-red-700 p-4"}
    [:p {:class "font-bold"} exception]
    [:p message]
    [:p (pr-str data)]]])

(defn display-edn [edn-data]
  (when edn-data
    [:div
     (for [[i row] (map-indexed vector edn-data)]
       ^{:key i} [highlight-code (pr-str row) "clojure"])]))

(def default-dml "[:put-docs :docs {:xt/id 1 :foo \"bar\"}]")
(def default-xtql-query "(from :docs [xt/id foo])")

(def default-sql-insert "INSERT INTO docs (xt$id, foo) VALUES (1, 'bar')")
(def default-sql-query "SELECT docs.xt$id, docs.name FROM docs")

(defn app []
  [:div {:class "flex flex-col h-screen"}
   [:header {:class "bg-gray-200 p-4 text-lg font-semibold shadow-md flex items-center justify-between"}
    [:div {:class "flex items-center space-x-4"}

     [:h2 "XT fiddle"]
     [:button  {:class "bg-blue-500 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded"
                :on-click (fn [event]
                            (.preventDefault event)
                            (rf/dispatch [:db-run]))}
      "Run"]

     [dropdown]]]

   [:div {:class "flex flex-1 overflow-hidden"}
    [:aside {:class "w-64 bg-gray-100 p-4 overflow-auto"}
     "Side"]
    [:div {:class "flex flex-col flex-1 overflow-hidden"}
     [:section {:class "flex flex-1 overflow-auto p-4"}
      [:div {:class "flex-1 bg-white border mr-4 p-4"}
       (if (= :xtql @(rf/subscribe [:get-type]))
         [editor/clj-editor default-dml {:change-callback (fn [txs] (rf/dispatch [:set-xtql-txs txs]))}]
         [editor/sql-editor default-sql-insert {:change-callback (fn [txs] (rf/dispatch [:set-sql-txs txs]))}])]
      [:div {:class "flex-1 bg-white border p-4"}
       (if (= :xtql @(rf/subscribe [:get-type]))
         [editor/clj-editor default-xtql-query {:change-callback  (fn [query] (rf/dispatch [:set-query query]))}]
         [editor/sql-editor default-sql-query {:change-callback  (fn [query] (rf/dispatch [:set-sql-query query]))}])]]
     [:section {:class "flex-1 bg-white p-4 border-t border-gray-300" :style {:flex-grow 1}}
      "Results:"
      (if @(rf/subscribe [:twirly?])
        [spinner]
        (let [{:keys [results failure] :as res} @(rf/subscribe [:results-or-failure])]
          (if failure
            [display-error failure]
            [display-edn results])))]]]])

;; start is called by init and after code reloading finishes
(defn ^:dev/after-load start! []
  (log/info :start "start")
  (hljs/registerLanguage "clojure" hljs-clojure)
  (rf/dispatch-sync [:app/init])
  (reagent.dom/render [app] (js/document.getElementById "app")))

(defn ^:export init []
  ;; init is called ONCE when the page loads
  ;; this is called in the index.html and must be exported
  ;; so it is available even in :advanced release builds
  (log/info :init "init")
  (start!))

;; this is called before any code is reloaded
(defn ^:dev/before-load stop []
  (log/info :stop "stop"))
