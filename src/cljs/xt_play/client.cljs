(ns xt-play.client
  (:require [xt-play.editor :as editor]
            [xt-play.run :as run]
            [xt-play.query-params :as query-params]
            [xt-play.clipboard :as clipboard]
            [xt-play.href :as href]
            [xt-play.highlight :as hl]
            [xt-play.tx-batch :as tx-batch]
            [xt-play.query :as query]
            [xt-play.dropdown :refer [dropdown]]
            [clojure.string :as str]
            [lambdaisland.glogi :as log]
            [re-frame.core :as rf]
            ["@heroicons/react/24/solid" :refer [ArrowUturnLeftIcon
                                                 PencilIcon
                                                 PlayIcon
                                                 XMarkIcon]]
            ["@heroicons/react/24/outline" :refer [BookmarkIcon
                                                   CheckCircleIcon]]))

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

(rf/reg-event-fx
  :update-url
  (fn [{:keys [db]} _]
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
     :fx [[:dispatch [::tx-batch/init [(tx-batch/default new-type)]]]
          [:dispatch [:update-url]]]}))

(rf/reg-event-db
  :set-query
  (fn [db [_ query]]
    (assoc db :query query)))

(rf/reg-event-fx
  :fx
  (fn [_ [_ effects]]
    {:fx effects}))

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
  [dropdown {:items [{:value :sql :label "SQL"}
                     {:value :xtql :label "XTQL"}]
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

(defn run-button []
  [button {:class "bg-blue-500 hover:bg-blue-700 text-white font-bold py-1 px-3 rounded-sm"
           :on-click #(rf/dispatch [::run/run])}
   [:div {:class "flex flex-row gap-1 items-center"}
    "Run"
    [:> PlayIcon {:class "h-5 w-5"}]]])

(defn copy-button []
  (let [copy-tick @(rf/subscribe [:copy-tick])]
    [:div {:class (str "p-2 flex flex-row gap-1 items-center select-none"
                       (when-not copy-tick
                         " hover:bg-gray-300 cursor-pointer"))
           :disabled copy-tick
           :on-click #(rf/dispatch-sync [:copy-url])}
     (if-not copy-tick
       [:<>
        "Copy URL"
        [:> BookmarkIcon {:class "h-5 w-5"}]]
       [:<>
        "Copied!"
        [:> CheckCircleIcon {:class "h-5 w-5"}]])]))

(defn header []
  [:header {:class "bg-gray-200 py-2 px-4"}
   [:div {:class "container mx-auto flex flex-col md:flex-row items-center gap-1"}
    [:div {:class "w-full flex flex-row items-center gap-4"}
     [:a {:href "/"}
      [:div {:class "flex flex-row items-center gap-1"}
       [:img {:class "h-8"
              :src "/public/images/xtdb-full-logo.svg"}]
       [title "Play"]]]
     [:span {:class "text-sm text-gray-400"}
      @(rf/subscribe [:version])]]
    [:div {:class "max-md:hidden flex-grow"}]
    [:div {:class "w-full flex flex-row items-center gap-1 md:justify-end"}
     [language-dropdown]
     [:div {:class "md:hidden flex-grow"}]
     [copy-button]
     [run-button]]]])

(defn reset-system-time-button [id]
  [:> ArrowUturnLeftIcon {:class "h-5 w-5 cursor-pointer"
                          :on-click #(rf/dispatch [:fx [[:dispatch [::tx-batch/assoc id :system-time nil]]
                                                        [:dispatch [:update-url]]]])}])

(defn input-system-time [id system-time]
  ;; TODO: Show the picker when someone clicks the edit button
  ;;       https://developer.mozilla.org/en-US/docs/Web/API/HTMLInputElement/showPicker
  [:input {:type "date"
           :value (-> system-time .toISOString (str/split #"T") first)
           :on-change #(rf/dispatch [:fx [[:dispatch [::tx-batch/assoc id :system-time (js/Date. (.. % -target -value))]]
                                          [:dispatch [:update-url]]]])
           :max (-> (js/Date.) .toISOString (str/split #"T") first)}])

(defn edit-system-time-button [id]
  [:> PencilIcon {:className "h-5 w-5 cursor-pointer"
                  :on-click #(rf/dispatch [:fx [[:dispatch [::tx-batch/assoc id :system-time (js/Date. (.toDateString (js/Date.)))]]
                                                [:dispatch [:update-url]]]])}])

(defn single-transaction [{:keys [editor id]} {:keys [system-time txs]}]
  [:div {:class "h-full flex flex-col"}
   (when system-time
     [:div {:class "flex flex-row justify-center items-center py-1 px-5 bg-gray-200"}
      [input-system-time id system-time]
      [reset-system-time-button id]])
   [editor {:class "border md:flex-grow min-h-36"
            :source txs
            :on-change #(rf/dispatch [:fx [[:dispatch [::tx-batch/assoc id :txs %]]
                                           [:dispatch [:update-url]]]])}]])

(defn multiple-transactions [{:keys [editor]} tx-batches]
  [:<>
   (for [[id {:keys [system-time txs]}] tx-batches]
     ^{:key id}
     [:div {:class "flex flex-col h-full"}
      [:div {:class "flex flex-row justify-between items-center py-1 px-5 bg-gray-200"}
       [:div {:class "w-full flex flex-row gap-2 justify-center items-center"}
        (if (nil? system-time)
          [:<>
           [:div "Current Time"]
           [edit-system-time-button id]]
          [:<>
           [input-system-time id system-time]
           [reset-system-time-button id]])]
       [:> XMarkIcon {:class "h-5 w-5 cursor-pointer"
                      :on-click #(rf/dispatch [:fx [[:dispatch [::tx-batch/delete id]]
                                                    [:dispatch [:update-url]]]])}]]
      [editor {:class "border md:flex-grow min-h-36"
               :source txs
               :on-change #(rf/dispatch [:fx [[:dispatch [::tx-batch/assoc id :txs %]]
                                              [:dispatch [:update-url]]]])}]])])

(defn transactions [{:keys [editor]}]
  [:div {:class "mx-4 md:mx-0 md:ml-4 md:flex-1 flex flex-col"}
   [:h2 "Transactions:"]
   ; NOTE: The min-h-0 somehow makes sure the editor doesn't
   ;       overflow the flex container
   [:div {:class "grow min-h-0 overflow-y-auto flex flex-col gap-2"}
    (let [tx-batches @(rf/subscribe [::tx-batch/id-batch-pairs])]
      (if (= 1 (count tx-batches))
        (let [[id batch] (first tx-batches)]
          [single-transaction {:editor editor
                               :id id}
           batch])
        [multiple-transactions {:editor editor}
         tx-batches]))
    [:div {:class "flex flex-row justify-center"}
     [:button {:class "w-10 h-10 bg-blue-500 hover:bg-blue-700 text-white font-bold py-1 rounded-full"
               :on-click #(rf/dispatch [:fx [[:dispatch [::tx-batch/append tx-batch/blank]]
                                             [:dispatch [:update-url]]]])}
      "+"]]]])

(defn query [{:keys [editor]}]
  [:div {:class "mx-4 md:mx-0 md:mr-4 md:flex-1 flex flex-col"}
   [:h2 "Query:"]
   [editor {:class "md:flex-grow h-full min-h-36 border"
            :source @(rf/subscribe [:query])
            :on-change #(rf/dispatch [:fx [[:dispatch [:set-query %]]
                                           [:dispatch [:update-url]]]])}]])

(defn results []
  [:section {:class "md:h-1/2 mx-4 flex flex-1 flex-col"}
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
  [:div {:class "flex flex-col h-dvh"}
   [header]
   ;; overflow-hidden fixes a bug where if an editor would have content that goes off the
   ;; screen the whole page would scroll.
   [:div {:class "py-2 flex-grow md:overflow-hidden h-full flex flex-col gap-2"}
    [:section {:class "md:h-1/2 flex flex-col md:flex-row flex-1 gap-2"}
     (let [editor (case @(rf/subscribe [:get-type])
                    :xtql editor/clj-editor
                    :sql editor/sql-editor)]
       [:<>
        [transactions {:editor editor}]
        [:hr {:class "md:hidden"}]
        [query {:editor editor}]
        [:div {:class "md:hidden flex flex-col items-center"}
         [run-button]]])]
    (when (or @(rf/subscribe [::run/loading?])
              @(rf/subscribe [::run/results?]))
      [:<>
       [:hr {:class "md:hidden"}]
       [results]])]])
