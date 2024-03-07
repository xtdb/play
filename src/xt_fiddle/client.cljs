(ns xt-fiddle.client
  (:require [clojure.string :as str]
            [ajax.core :as ajax]
            [day8.re-frame.http-fx]
            [lambdaisland.glogi :as log]
            [lambdaisland.glogi.console :as glogi-console]
            [re-frame.core :as rf]
            [reagent.dom]
            [xt-fiddle.editor :as editor]
            [xt-fiddle.query-params :as query-params]
            [xt-fiddle.dropdown :refer [dropdown]]
            ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/clojure" :as hljs-clojure]))

(glogi-console/install!)

(log/set-levels
 {:glogi/root   :info})    ;; Set a root logger level, this will be inherited by all loggers
  ;; 'my.app.thing :trace  ;; Some namespaces you might want detailed logging

(rf/reg-event-fx
  :app/init
  [(rf/inject-cofx ::query-params/get)]
  (fn [{:keys [_db] {:keys [type txs query]} :query-params}
       _]
    {:db {:type (if type (keyword type) :sql)
          :txs (when txs (js/atob txs))
          :query (when query (js/atob query))}}))

(rf/reg-event-fx
  :share
  (fn [{:keys [db]}]
    {::query-params/set {:type (name (:type db))
                         :txs (js/btoa (:txs db))
                         :query (js/btoa (:query db))}}))

(rf/reg-event-db
  :dropdown-selection
  (fn [db [_ selection]]
    (-> db
        (assoc :type selection)
        (dissoc :txs :query))))

(rf/reg-event-db
  :set-txs
  (fn [db [_ txs]]
    (assoc db :txs txs)))

(rf/reg-event-db
  :set-query
  (fn [db [_ query]]
    (assoc db :query query)))

(rf/reg-sub
  :get-type
  (fn [db _]
    (:type db)))

(rf/reg-sub
  :txs
  (fn [db _]
    (:txs db)))

(rf/reg-sub
  :query
  (fn [db _]
    (:query db)))

(rf/reg-sub
  :app/loading
  :-> :app/loading)

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

(defn encode-txs [txs type]
  (str "["
       (case type
         :sql (->> (str/split txs #";")
                   (remove str/blank?)
                   (map #(str [:sql %]))
                   str/join)
         :xtql txs)
       "]"))

(defn encode-query [query type]
  (case type
    :sql (pr-str query)
    :xtql query))

(rf/reg-event-fx
  :db-run
  (fn [{:keys [db]} _]
    (when-not (:app/loading db)
      {:db (-> db
               (assoc :show-twirly true)
               (dissoc :failure :results))
       :http-xhrio {:method :post
                    :uri "/db-run"
                    :params {:txs (encode-txs (:txs db) (:type db))
                             :query (encode-query (:query db) (:type db))}
                    :timeout 3000
                    :format (ajax/json-request-format)
                    :response-format (ajax/json-response-format {:keywords? true})
                    :on-success [:success-results]
                    :on-failure [:failure-results]}})))

(rf/reg-sub
  :twirly?
  (fn [db _]
    (:show-twirly db)))

(rf/reg-sub
  :results-or-failure
  (fn [db _]
    (select-keys db [:results :failure])))


(defn language-dropdown []
  [dropdown {:items [{:value :xtql :label "XTQL"}
                     {:value :sql :label "SQL"}]
             :on-click #(rf/dispatch [:dropdown-selection (:value %)])
             :label (case @(rf/subscribe [:get-type])
                      :xtql "XTQL"
                      :sql "SQL")}])

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
(def default-sql-query "SELECT docs.xt$id, docs.foo FROM docs")

(defn page-spinner []
  [:div {:class "fixed flex items-center justify-center h-screen w-screen bg-white/80 z-50"}
   "Loading..."])

(defn title [& body]
  (into [:h2 {:class "text-lg font-semibold"}]
        body))

(defn button [opts & body]
  (into [:button (merge {:class "bg-blue-500 hover:bg-blue-700 text-white font-bold py-1 px-3 rounded-sm"}
                        opts)]
        body))

(defn app []
  [:div {:class "flex flex-col h-screen"}
   (when @(rf/subscribe [:app/loading])
     [page-spinner])

   [:header {:class "bg-gray-200 py-2 shadow-md"}
    [:div {:class "container mx-auto flex items-center space-x-4"}
     [title "XT fiddle"]
     [language-dropdown]
     [button {:on-click #(rf/dispatch [:share])}
      [title "Share"]]
     [:div {:class "flex-grow"}]
     [button {:on-click #(rf/dispatch [:db-run])}
      [title "Run!"]]]]

   ;; overflow-hidden fixes a bug where if an editor would have content that goes off the
   ;; screen the whole page would scroll.
   [:div {:class "container mx-auto flex-grow overflow-hidden"}
    [:div {:class "h-full flex flex-col gap-2 py-2"}
     [:section {:class "h-1/2 flex gap-2"}
      [:div {:class "flex flex-1 border overflow-scroll"}
       (if (= :xtql @(rf/subscribe [:get-type]))
         [editor/clj-editor (or @(rf/subscribe [:txs]) default-dml)
          {:change-callback (fn [txs] (rf/dispatch [:set-txs txs]))}]
         [editor/sql-editor (or @(rf/subscribe [:txs]) default-sql-insert)
          {:change-callback (fn [txs] (rf/dispatch [:set-txs txs]))}])]
      [:div {:class "flex flex-1 border overflow-scroll"}
       (if (= :xtql @(rf/subscribe [:get-type]))
         [editor/clj-editor (or @(rf/subscribe [:query]) default-xtql-query)
          {:change-callback  (fn [query] (rf/dispatch [:set-query query]))}]
         [editor/sql-editor (or @(rf/subscribe [:query]) default-sql-query)
          {:change-callback  (fn [query] (rf/dispatch [:set-query query]))}])]]
     [:section {:class "h-1/2 border p-2 overflow-auto"}
      "Results:"
      (if @(rf/subscribe [:twirly?])
        [spinner]
        (let [{:keys [results failure]} @(rf/subscribe [:results-or-failure])]
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
