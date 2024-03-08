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
            ["highlight.js/lib/languages/clojure" :as hljs-clojure]
            ["highlight.js/lib/languages/json" :as hljs-json]))

(glogi-console/install!)

(log/set-levels
 {:glogi/root   :info})    ;; Set a root logger level, this will be inherited by all loggers
  ;; 'my.app.thing :trace  ;; Some namespaces you might want detailed logging

(def default-xtql-query "(from :docs [xt/id foo])")
(def default-sql-query "SELECT docs.xt$id, docs.foo FROM docs")
(defn default-query [type]
  (case type
    :xtql default-xtql-query
    :sql default-sql-query))

(def default-dml "[:put-docs :docs {:xt/id 1 :foo \"bar\"}]")
(def default-sql-insert "INSERT INTO docs (xt$id, foo) VALUES (1, 'bar')")
(defn default-txs [type]
  (case type
    :xtql default-dml
    :sql default-sql-insert))

(rf/reg-event-fx
  :app/init
  [(rf/inject-cofx ::query-params/get)]
  (fn [{:keys [query-params]} _]
    (let [{:keys [type txs query]} query-params
          type (if type (keyword type) :sql)]
      {:db {:type type
            :txs (if txs
                   (js/atob txs)
                   (default-txs type))
            :query (if query
                     (js/atob query)
                     (default-query type))}})))

(rf/reg-event-fx
  :share
  (fn [{:keys [db]}]
    {::query-params/set {:type (name (:type db))
                         :txs (js/btoa (:txs db))
                         :query (js/btoa (:query db))}}))

(rf/reg-event-db
  :dropdown-selection
  (fn [db [_ new-type]]
    (-> db
        (assoc :type new-type)
        (assoc :txs (default-txs new-type))
        (assoc :query (default-query new-type)))))

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
  :-> :type)

(rf/reg-sub
  :txs
  :-> :txs)

(rf/reg-sub
  :query
  :-> :query)

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
  :-> :show-twirly)

(rf/reg-sub
  :results-or-failure
  :-> #(-> % (select-keys [:results :failure])))


(defn language-dropdown []
  [dropdown {:items [{:value :xtql :label "XTQL"}
                     {:value :sql :label "SQL"}]
             :selected @(rf/subscribe [:get-type])
             :on-click #(rf/dispatch [:dropdown-selection (:value %)])
             :label (case @(rf/subscribe [:get-type])
                      :xtql "XTQL"
                      :sql "SQL")}])

(defn render-raw-html [html-content]
  [:div {:dangerouslySetInnerHTML {:__html html-content}}])

(defn highlight-code [{:keys [language]} code]
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

(defn table-order [a b]
  (cond
    (= a b) 0
    (= a :xt/id) -1
    (= b :xt/id) 1
    :else (compare a b)))

(defn display-table [results type]
  (when results
    (let [all-keys (->> results
                        (mapcat keys)
                        (into #{})
                        (sort table-order))]
      [:table {:class "table-auto w-full"}
       [:thead
        [:tr {:class "border-b"}
         (for [k all-keys]
           ^{:key k}
           [:th {:class "text-left p-4"}
            (-> k symbol str)])]]
       [:tbody
        (for [[i row] (map-indexed vector results)]
          ^{:key i}
          [:tr {:class "border-b"}
           (for [k all-keys]
             ^{:key k}
             [:td {:class "text-left p-4"}
              (let [value (get row k)]
                (if (and (not (string? value))
                         (seqable? value))
                  (case type
                    :xtql [highlight-code {:language "clojure"}
                           (pr-str value)]
                    :sql [highlight-code {:language "json"}
                          (js/JSON.stringify (clj->js value))])
                  value))])])]])))

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
      (let [editor (case @(rf/subscribe [:get-type])
                     :xtql editor/clj-editor
                     :sql editor/sql-editor)]
        [:<>
         [:div {:class "flex-1 flex flex-col"}
          [:h2 "Transactions:"]
          ; NOTE: The min-h-0 somehow makes sure the editor doesn't
          ;       overflow the flex container
          [:div {:class "grow min-h-0"}
           [editor {:source @(rf/subscribe [:txs])
                    :change-callback #(rf/dispatch [:set-txs %])}]]]
         [:div {:class "flex-1 flex flex-col"}
          [:h2 "Query:"]
          [:div {:class "grow min-h-0"}
           [editor {:source @(rf/subscribe [:query])
                    :change-callback #(rf/dispatch [:set-query %])}]]]])]
     [:section {:class "h-1/2 flex flex-col"}
      [:h2 "Results:"]
      [:div {:class "grow min-h-0 border p-2 overflow-auto"}
       (if @(rf/subscribe [:twirly?])
         [spinner]
         (let [{:keys [results failure]} @(rf/subscribe [:results-or-failure])]
           (if failure
             [display-error failure]
             [display-table results @(rf/subscribe [:get-type])])))]]]]])

;; start is called by init and after code reloading finishes
(defn ^:dev/after-load start! []
  (log/info :start "start")
  (hljs/registerLanguage "clojure" hljs-clojure)
  (hljs/registerLanguage "json" hljs-json)
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
