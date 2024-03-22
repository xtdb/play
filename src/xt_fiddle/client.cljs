(ns xt-fiddle.client
  (:require [xt-fiddle.editor :as editor]
            [xt-fiddle.run :as run]
            [xt-fiddle.query-params :as query-params]
            [xt-fiddle.highlight :as hl]
            [xt-fiddle.tx-batch :as tx-batch]
            [xt-fiddle.query :as query]
            [xt-fiddle.dropdown :refer [dropdown]]
            [clojure.string :as str]
            [lambdaisland.glogi :as log]
            [re-frame.core :as rf]
            ["@heroicons/react/24/solid" :refer [ArrowUturnLeftIcon
                                                 PencilIcon
                                                 PlayIcon
                                                 XMarkIcon]]
            ["@heroicons/react/24/outline" :refer [BookmarkIcon]]))

(rf/reg-event-fx
  :share
  (fn [{:keys [db]}]
    {::query-params/set {:version (:version db)
                         :type (name (:type db))
                         :txs (tx-batch/param-encode (tx-batch/list db))
                         :query (js/btoa (:query db))}}))

(rf/reg-event-fx
  :dropdown-selection
  (fn [{:keys [db]} [_ new-type]]
    {:db (-> db
             (assoc :type new-type)
             (assoc :query (query/default new-type)))
     :dispatch [::tx-batch/init [(tx-batch/default new-type)]]}))

(rf/reg-event-db
  :set-query
  (fn [db [_ query]]
    (assoc db :query query)))

(rf/reg-sub
  :get-type
  :-> :type)

(rf/reg-sub
  :query
  :-> :query)

(rf/reg-sub
  :version
  :-> :version)

(defn language-dropdown []
  [dropdown {:items [{:value :xtql :label "XTQL"}
                     {:value :sql :label "SQL"}]
             :selected @(rf/subscribe [:get-type])
             :on-click #(rf/dispatch [:dropdown-selection (:value %)])
             :label (case @(rf/subscribe [:get-type])
                      :xtql "XTQL"
                      :sql "SQL")}])

(defn spinner []
  [:div "Loading..."])

(defn display-error [{:keys [exception message data]}]
  [:div {:class "flex flex-col gap-2"}
   [:div {:class "bg-red-100 border-l-4 border-red-500 text-red-700 p-4"}
    [:p {:class "font-bold"} (str "Error: " exception)]
    [:p {:class "whitespace-pre-wrap font-mono"}
     (->> (str/split message #"(?:\r\n|\r|\n)")
          (map #(do [:span %]))
          (interpose [:br]))]
    (when (seq data)
      [:<>
       [:p {:class "pt-2 font-semibold"}
        "Data:"]
       [:p (pr-str data)]])]])

(defn table-order [a b]
  (cond
    (= a b) 0
    (= a :xt$id) -1
    (= b :xt$id) 1
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
                (case type
                  :xtql [hl/code {:language "clojure"}
                         (pr-str value)]
                  :sql [hl/code {:language "json"}
                        (js/JSON.stringify (clj->js value))]))])])]])))

(defn title [& body]
  (into [:h2 {:class "text-lg font-semibold"}]
        body))

(defn button [opts & body]
  (into [:button (merge {:class "bg-blue-500 hover:bg-blue-700 text-white font-bold py-1 px-3 rounded-sm"}
                        opts)]
        body))

(defn header []
  [:header {:class "bg-gray-200 py-2"}
   [:div {:class "container mx-auto flex items-center space-x-4"}
    [:div {:class "flex flex-row items-center gap-1"}
     [:img {:class "h-8"
            :src "/public/images/xtdb-full-logo.svg"}]
     [title "Fiddle"]]
    [language-dropdown]
    [:span {:class "text-sm text-gray-400"}
     @(rf/subscribe [:version])]
    [:div {:class "flex-grow"}]
    [:div {:class "p-2 hover:bg-gray-300 cursor-pointer flex flex-row gap-1 items-center"
           :on-click #(rf/dispatch [:share])}
     "Save as URL"
     [:> BookmarkIcon {:class "h-5 w-5"}]]
    [button {:class "bg-blue-500 hover:bg-blue-700 text-white font-bold py-1 px-3 rounded-sm"
             :on-click #(rf/dispatch [::run/run])}
     [:div {:class "flex flex-row gap-1 items-center"}
      "Run"
      [:> PlayIcon {:class "h-5 w-5"}]]]]])

(defn single-transaction [{:keys [editor id]} {:keys [system-time txs]}]
  [:div {:class "h-full flex flex-col"}
   (when system-time
     [:div {:class "flex flex-row justify-center items-center py-1 px-5 bg-gray-200"}
      [:input {:type "date"
               :value (-> system-time .toISOString (str/split #"T") first)
               :on-change #(rf/dispatch [::tx-batch/assoc id :system-time (js/Date. (.. % -target -value))])
               :max (-> (js/Date.) .toISOString (str/split #"T") first)}]
      [:> ArrowUturnLeftIcon {:class "h-5 w-5 cursor-pointer"
                              :on-click #(rf/dispatch [::tx-batch/assoc id :system-time nil])}]])
   [editor {:class "flex-grow border"
            :source txs
            :change-callback #(rf/dispatch [::tx-batch/assoc id :txs %])}]])

(defn multiple-transactions [{:keys [editor]} tx-batches]
  [:<>
   (for [[id {:keys [system-time txs]}] tx-batches]
     ^{:key id}
     [:div
      [:div {:class "flex flex-row justify-between items-center py-1 px-5 bg-gray-200"}
       [:div {:class "w-full flex flex-row gap-2 justify-center items-center"}
        (if (nil? system-time)
          [:<>
           [:div "Current Time"]
           [:> PencilIcon {:className "h-5 w-5 cursor-pointer"
                           :on-click #(rf/dispatch [::tx-batch/assoc id :system-time (js/Date. (.toDateString (js/Date.)))])}]]
          [:<>
           [:input {:type "date"
                    :value (-> system-time .toISOString (str/split #"T") first)
                    :on-change #(rf/dispatch [::tx-batch/assoc id :system-time (js/Date. (.. % -target -value))])
                    :max (-> (js/Date.) .toISOString (str/split #"T") first)}]
           [:> ArrowUturnLeftIcon {:class "h-5 w-5 cursor-pointer"
                                   :on-click #(rf/dispatch [::tx-batch/assoc id :system-time nil])}]])]
       [:> XMarkIcon {:class "h-5 w-5 cursor-pointer"
                      :on-click #(rf/dispatch [::tx-batch/delete id])}]]
      [editor {:class "border"
               :source txs
               :change-callback #(rf/dispatch [::tx-batch/assoc id :txs %])}]])])

(defn transactions [{:keys [editor]}]
  [:div {:class "flex-1 flex flex-col"}
   [:h2 "Transactions:"]
   ; NOTE: The min-h-0 somehow makes sure the editor doesn't
   ;       overflow the flex container
   [:div {:class "grow min-h-0 overflow-y-auto flex flex-col gap-2"}
    (let [tx-batches @(rf/subscribe [::tx-batch/id->batch])]
      (if (= 1 (count tx-batches))
        (let [[id batch] (first tx-batches)]
          [single-transaction {:editor editor
                               :id id}
           batch])
        [multiple-transactions {:editor editor}
         tx-batches]))
    [:button {:class "w-full bg-blue-100 hover:bg-blue-200 text-white font-bold py-1 rounded-full"
              :on-click #(rf/dispatch [::tx-batch/append tx-batch/blank])}
     "+"]]])

(defn query [{:keys [editor]}]
  [:div {:class "flex-1 flex flex-col"}
   [:h2 "Query:"]
   [:div {:class "grow min-h-0"}
    [editor {:class "border h-full"
             :source @(rf/subscribe [:query])
             :change-callback #(rf/dispatch [:set-query %])}]]])

(defn results []
  [:<>
   [:h2 "Results:"]
   [:div {:class "grow min-h-0 border p-2 overflow-auto"}
    (if @(rf/subscribe [::run/loading?])
      [spinner]
      (let [{::run/keys [results failure]} @(rf/subscribe [::run/results-or-failure])]
        (if failure
          [display-error failure]
          (cond
            (empty? results) "No results returned"
            (every? empty? results) (str (count results) " empty row(s) returned")
            :else [display-table results @(rf/subscribe [:get-type])]))))]])

(defn app []
  [:div {:class "flex flex-col h-screen"}
   [header]
   ;; overflow-hidden fixes a bug where if an editor would have content that goes off the
   ;; screen the whole page would scroll.
   [:div {:class "mx-4 flex-grow overflow-hidden"}
    [:div {:class "h-full flex flex-col gap-2 py-2"}
     [:section {:class "h-1/2 flex flex-1 gap-2"}
      (let [editor (case @(rf/subscribe [:get-type])
                     :xtql editor/clj-editor
                     :sql editor/sql-editor)]
        [:<>
         [transactions {:editor editor}]
         [query {:editor editor}]])]
     (when (or @(rf/subscribe [::run/loading?])
               @(rf/subscribe [::run/results?]))
       [:section {:class "h-1/2 flex flex-1 flex-col"}
        [results]])]]])
