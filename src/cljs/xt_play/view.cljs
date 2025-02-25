(ns xt-play.view
  (:require ["@heroicons/react/24/outline"
             :refer [BookmarkIcon CheckCircleIcon]]
            ["@heroicons/react/24/solid"
             :refer [PlayIcon XMarkIcon BugAntIcon]]
            ["react-svg-spinners" :refer [SixDotsScale]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [xt-play.components.dropdown :refer [dropdown]]
            [xt-play.components.editor :as editor]
            [xt-play.components.highlight :as hl]
            [xt-play.config :as config]
            [xt-play.model.client :as model]
            [xt-play.model.run :as run]
            [xt-play.model.tx-batch :as tx-batch]))

;; Todo
;; - pull out components to own ns

(defn- language-dropdown [tx-type]
  [dropdown {:items model/items
             :selected tx-type
             :on-click #(rf/dispatch [:dropdown-selection (:value %)])
             :label (get-in config/tx-types [tx-type :label])}])

(defn- spinner [] [:div {:class "flex justify-center items-center h-full"}
                   [:> SixDotsScale]])

(defn get-value [ref]
  (when-let [refd @ref]
    (let [doc (.. (:ref refd) -view -viewState -state -doc)
          text-lines (if-let [children (.-children doc)]
                       ;; get each chile's text
                       (.map children (fn [c] (.. c -text (join "\n"))))
                       ;; no children - just get text
                       (.-text doc))]
      (.join text-lines "\n"))))

(defn- editor-update-opts [id source ref]
  {:source source
   :editor-ref ref
   :on-blur #(do
               (rf/dispatch [::tx-batch/assoc id :txs (get-value ref)])
               (rf/dispatch [:update-url]))})

(defn- display-error [{:keys [exception message data]} position]
  ^{:key position}
  [:div {:class "flex flex-col gap-2"}
   [:div {:class "bg-red-100 border-l-4 border-red-500 text-red-700 p-4"}
    [:p {:class "font-bold"} (str "Error: " exception)]
    [:p {:class "whitespace-pre-wrap font-mono"}
     (->> (str/split message #"(?:\r\n|\r|\n)")
          (map-indexed #(do ^{:key (str "span-" %1)} [:<> [:span %2] [:br]])))]
    (when (seq data)
      [:<>
       [:p {:class "pt-2 font-semibold"}
        "Data:"]
       [:p (pr-str data)]])]])

(defn- print-array [arr]
  (-> (reduce (fn [acc v]
                (str acc
                     (cond
                       (vector? v) (print-array v)
                       (map? v) (-> (reduce-kv
                                     (fn [acc k val]
                                       (str acc
                                            (if (keyword? k) (name k) k)
                                            ": "
                                            (if (vector? val)
                                              (print-array val)
                                              val) ", "))
                                     "{"
                                     v)
                                    (str/replace #", $" "}"))
                       :else v)
                     ", "))
              "["
              arr)
      (str/replace #", $" "]")))

(defn- display-table [results tx-type position]
  (when results
    ^{:key position}
    [:table {:class "table-auto w-full"}
     [:thead
      [:tr {:class "border-b"}
       (for [label (first results)]
         ^{:key label}
         [:th {:class "text-left p-4"} label])]]
     [:tbody
      (doall
       (for [[i row] (map-indexed vector (rest results))]
         ^{:key (str "row-" i)}
         [:tr {:class "border-b"}
          (doall
           (for [[ii value] (map-indexed vector row)]
             ^{:key (str "row-" i " col-" ii)}
             [:td {:class "text-left p-4"}
              (case @tx-type
                :xtql
                [hl/code {:language "clojure"}
                 (pr-str value)]
                ;; default
                [hl/code {:language "json"}
                 (if (vector? value)
                   (print-array value)
                   (str value))])]))]))]]))

(defn- title [& body]
  (into [:h2 {:class "text-lg font-semibold"}]
        body))

(defn- update-db-from-editor-refs [tx-refs]
  (doseq [tx-ref @tx-refs]
    (let [value (get-value tx-ref)]
      (if (empty? value)
        (rf/dispatch [::tx-batch/delete (:id @tx-ref)])
        (rf/dispatch [::tx-batch/assoc (:id @tx-ref) :txs (get-value tx-ref)])))))

(def icon-size "h-5 w-5")

(def icon-pointer (str icon-size " cursor-pointer"))

(defn- run-button [tx-refs]
  (let [loading? (rf/subscribe [::run/loading?])
        show-results? (rf/subscribe [::run/show-results?])]
    [:<>
     [:button {:class "bg-blue-500 hover:bg-blue-700 text-white font-bold py-1 px-3 rounded-sm"
               :disabled @loading?
               :opacity (if @loading? 0.4 1)
               :on-click #(do
                            (update-db-from-editor-refs tx-refs)
                            (rf/dispatch [::run/run]))}
      [:div {:class "flex flex-row gap-1 items-center"
             :title "Press 'CMD+ENTER' or 'CTRL+ENTER' to run"}
       "Run"
       [:> PlayIcon {:class icon-size}]]]
     [:> XMarkIcon {:class icon-pointer
                    :visibility (if (and (not @loading?) @show-results?) "visible" "hidden")
                    :on-click #(rf/dispatch [::run/hide-results!])}]]))

(defn- copy-button [tx-refs]
  (let [copy-tick (rf/subscribe [:copy-tick])]
    (fn []
      [:div {:class (str "p-2 flex flex-row gap-1 items-center select-none"
                         (when-not @copy-tick
                           " hover:bg-gray-300 cursor-pointer"))
             :disabled @copy-tick
             :on-click (fn [_]
                         ;; given that it's no longer controlled component - need to get values
                         ;; of the editor from the refs
                         (update-db-from-editor-refs tx-refs)

                         (rf/dispatch-sync [:update-url])
                         (rf/dispatch-sync [:copy-url]))}

       (if-not @copy-tick
         [:div {:class "flex flex-row items-center gap-1"
                :title "Share your state by Copying the URL"}
          "Copy URL"
          [:> BookmarkIcon {:class icon-size}]]
         [:div {:class "flex flex-row items-center gap-4"}
          "Copied!"
          [:> CheckCircleIcon {:class icon-size}]])])))

(def ^:private logo
  [:a {:href "/"}
   [:div {:class "flex flex-row items-center gap-1"}
    [:img {:class "h-8"
           :src "/public/images/xtdb-full-logo.svg"}]
    [title "Play"]]])

(defn- header [tx-type tx-refs]
  [:header {:class "max-md:sticky top-0 z-50 bg-gray-200 py-2 px-4"}
   [:div {:class "container mx-auto flex flex-col md:flex-row items-center gap-1"}
    [:div {:class "w-full flex flex-row items-center gap-4"}
     logo
     [:span {:class "text-sm text-gray-400"}
      @(rf/subscribe [:version])]
     [:div {:class "flex flex-row"}
      [:a {:class "text-sm text-gray-400"
           :href "https://docs.xtdb.com/quickstart/sql-overview"}
       "SQL Overview"]]
     [:div {:class "flex flex-row text-sm text-gray-400"}
      [:a {:class icon-pointer
           :href "https://github.com/xtdb/xt-fiddle/issues/new"}
       [:> BugAntIcon {:title "See a bug? Report it here!"}]]]]
    [:div {:class "max-md:hidden flex-grow"}]
    [:div {:class "w-full flex flex-row items-center gap-1 md:justify-end"}
     [language-dropdown tx-type]
     [:div {:class "md:hidden flex-grow"}]
     [copy-button tx-refs]
     [run-button tx-refs]]]])

(def ^:private initial-message [:p {:class "text-gray-400"} "Enter a statement to see results"])
(def ^:private no-results-message [:div {:class "pl-2 pt-2"}
                                   "No results returned"])
(defn- empty-rows-message [results] [:div {:class "pl-2 pt-2"}
                                     (str (count results) " empty row(s) returned")])

(defn- display-tx-result [tx-type idx row]
  (let [[[msg-k _] & more] row]
    (case msg-k
      "message" (let [[[msg exc data]] more]
                  ^{:key idx} [display-error {:message msg
                                              :exception exc
                                              :data data} idx])
      "next$jdbc$update_count" ^{:key idx} [:p.mb-2.mt-2.mx-2 "Statement succeeded."]
      ^{:key idx} [display-table row tx-type idx])))

(defn- tx-result-or-error? [row]
  (let [[[[msg-k exc data] _]] row]
    (or (= "next$jdbc$update_count" msg-k)
        (= ["message" "exception" "data"] [msg-k exc data]))))

(defn- spacer-header [cnt children]
  [:div
   (when (> cnt 1)
     [:div {:class "flex flex-row justify-between items-center py-1 px-5 bg-gray-200 "}
      [:> XMarkIcon {:class icon-size
                     :visibility "hidden"}]])
   children])

(def half-window-col-class (str "md:mx-0 flex-1 flex flex-col "
                                ;; stop editor expanding beyond the viewport
                                "md:max-w-[48vw] lg:max-w-[49vw] "))

(defn- prune-tx-results [results]
  (if (every? tx-result-or-error?
              (map vector results))
    (drop 1 (drop-last results))
    (:pruned
     (reduce (fn [{:keys [tx-err] :as acc} res]
               (if (tx-result-or-error? [res])
                 (cond
                   (= 1 tx-err) (-> acc
                                    (update :tx-err inc)
                                    (update :pruned conj res))
                   (= 2 tx-err) (assoc acc :tx-err 0)
                   :else (update acc :tx-err inc))
                 (update acc :pruned conj res)))
             {:tx-err 0
              :pruned []}
             results))))

(defn- tx-results
  "If there is a system-time - there was a transaction, so discard those results."
  [{:keys [system-time]} the-result tx-type]
  (if system-time
    (map-indexed (partial display-tx-result tx-type) (prune-tx-results the-result))
    (map-indexed (partial display-tx-result tx-type) the-result)))

(defn- results [position]
  (let [tx-batches @(rf/subscribe [::tx-batch/id-batch-pairs])
        statements (second (get tx-batches position))
        tx-type (rf/subscribe [:get-type])
        loading? (rf/subscribe [::run/loading?])
        show-results? (rf/subscribe [::run/show-results?])
        results-or-failure (rf/subscribe [::run/results-or-failure])]
    (when (or @loading?
              @show-results?)
      ^{:key position}
      [:div {:class (str half-window-col-class
                         "grow min-h-32 border overflow-auto mx-4")}
       (if @loading?
         [spinner]
         (let [{::run/keys [results failure response?]} @results-or-failure]
           (if failure
             [display-error failure position]
             (let [the-result (get results position)]
               (if (some tx-result-or-error? (map vector the-result))
                 [spacer-header (count results)
                  (tx-results statements the-result tx-type)]
                 (cond
                   (not response?) [spacer-header (count results)
                                    initial-message]
                   (empty? results) [spacer-header (count results)
                                     no-results-message]
                   (= [[[]]] the-result) [spacer-header (count results)
                                          no-results-message]
                   (every? #(= [[]] %) the-result) [spacer-header (count results)
                                                    (empty-rows-message the-result)]
                   :else
                   (map-indexed (fn [idx sub-result]
                                  ^{:key idx}
                                  [spacer-header (count results)
                                   [display-table sub-result tx-type position]])
                                the-result)))))))])))

(defn- rm-stmt-header [id system-time position]
  [:div {:class "flex flex-row justify-between items-center py-1 px-5 bg-gray-200 "}
   [:div {:class "w-full flex flex-row gap-2 justify-center items-center"}
    (when system-time (str system-time))]
   [:> XMarkIcon {:class icon-pointer
                  :on-click #(rf/dispatch [:fx [[:dispatch [::tx-batch/delete id]]
                                                [:dispatch [::run/delete-result position]]
                                                [:dispatch [:update-url]]]])}]])

(defn- add-statement-button []
  [:div {:class "flex flex-row"}
   [:div {:class "flex-col flex-1"}
    [:div {:class "flex flex-row justify-center"}
     [:button {:class "w-10 h-10 bg-blue-500 hover:bg-blue-700 text-white font-bold py-1 rounded-full"
               :on-click #(rf/dispatch [:fx [[:dispatch [::tx-batch/append tx-batch/blank]]
                                             [:dispatch [:update-url]]
                                             [:dispatch [::run/hide-results!]]]])}
      "+"]]]
   [:div {:class "flex-col flex-1"
          :visibility "hidden"}]])

(defn- statements [{:keys [editor tx-refs]} tx-type]
  (reset! tx-refs [])
  (let [statements @(rf/subscribe [::tx-batch/id-batch-pairs])]
    [:<>
     [:<>
      (doall
       (map-indexed
        (fn [idx [id {:keys [txs system-time]}]]
          (let [ref (atom {:id id :ref nil})]
            (swap! tx-refs conj ref)
            ^{:key id}
            [:<>
             [:div {:class "flex flex-col md:flex-row gap-y-16"}
              [:div {:class (str half-window-col-class
                                 "mx-4 md:pr-4 grow min-h-0 lg:overflow-y-auto ")}
               (when (< 1 (count statements))
                 [rm-stmt-header id system-time idx])
               [editor (merge
                        (editor-update-opts id txs ref)
                        {:class "md:flex-grow min-h-36 border"})]]

              [results idx]]]))
        statements))]
     (when (#{:sql-v2 :xtql} tx-type)
       [add-statement-button])]))

(def ^:private mobile-gap [:hr {:class "md:hidden"}])

(defn app []
  (let [tx-type (rf/subscribe [:get-type])
        tx-refs (atom [])]
    (fn []
      [:div {:class "flex flex-col h-dvh"}
       [header @tx-type tx-refs]
       ;; overflow-hidden fixes a bug where if an editor would have content that
       ;; goes off the screen the whole page would scroll.
       [:div {:class "py-2 px-4 flex-grow  h-full flex flex-row md:flex-row gap-2 "}
        (let [ctx {:editor (editor/default-editor @tx-type)
                   :tx-refs tx-refs}]
          [:div {:class "flex flex-col gap-2 w-full"}
           [statements ctx @tx-type]
           mobile-gap])]])))
