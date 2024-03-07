(ns xt-fiddle.editor
  (:require ["@codemirror/autocomplete" :refer [autocompletion]]
            ["@codemirror/commands" :refer [history historyKeymap]]
            ["@codemirror/language" :refer [foldGutter syntaxHighlighting defaultHighlightStyle]]
            ["@codemirror/lang-sql" :as sql :refer [PostgreSQL StandardSQL keywordCompletionSource]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :as view :refer [EditorView lineNumbers]]
            [applied-science.js-interop :as j]
            [nextjournal.clojure-mode :as cm-clj]
            [reagent.core :as r]))

(def theme
  (.theme EditorView
          (j/lit {"&.cm-editor" {:height "100%"}
                  ".cm-content" {:white-space "pre-wrap"
                                 :padding "10px 0"
                                 :flex "1 1 0"}

                  "&.cm-focused" {:outline "0 !important"}
                  ".cm-line" {:padding "0 9px"
                              :line-height "1.6"
                              :font-size "16px"
                              :font-family "var(--code-font)"}
                  ".cm-matchingBracket" {:border-bottom "1px solid var(--teal-color)"
                                         :color "inherit"}
                  ".cm-gutters" {:background "transparent"
                                 :padding "0 9px"
                                 :line-height "1.6"
                                 :font-size "16px"
                                 :font-family "var(--code-font)"}
                  ;; only show cursor when focused
                  ".cm-cursor" {:visibility "hidden"}
                  "&.cm-focused .cm-cursor" {:visibility "visible"}})))

(defn on-change [callback]
  (view/EditorView.updateListener.of (fn [update]
                                       (when (.-changes update)
                                         (callback (.. update -state -doc toString))))))

(defonce clj-extensions
  #js [theme
       (history)
       (syntaxHighlighting defaultHighlightStyle)
       (view/drawSelection)
       (foldGutter)
       (lineNumbers)
       (.. EditorState -allowMultipleSelections (of true))
       cm-clj/default-extensions
       (.of view/keymap cm-clj/complete-keymap)
       (.of view/keymap historyKeymap)])

(def sql-extensions
  #js [theme
       (history)
       (syntaxHighlighting defaultHighlightStyle)
       (view/drawSelection)
       (foldGutter)
       (lineNumbers)
       (.. EditorState -allowMultipleSelections (of true))
       (.of view/keymap historyKeymap)
       StandardSQL
       (.. StandardSQL -language -data (of #js {:autocomplete (keywordCompletionSource StandardSQL true)}))
       #_(autocompletion #js {:override #js [(keywordCompletionSource PostgreSQL true)]})])

(defn make-view [{:keys [state parent]}]
  (new EditorView #js{:state state :parent parent}))

(defn make-state [{:keys [doc extensions]}]
  (.create EditorState #js{:doc doc :extensions (clj->js extensions)}))

(defn editor [{:keys [extensions]}]
  (fn [{:keys [source change-callback]}]
    (r/with-let [!view (r/atom nil)
                 mount! (fn [el]
                          (when el
                            (reset! !view
                                    (let [extensions [extensions
                                                      (on-change change-callback)]
                                          state (make-state {:doc source
                                                             :extensions extensions})]
                                      (make-view
                                       {:parent el
                                        :state state})))))]
      [:div {:class "h-full"
             :ref mount!}]
      (finally
        (j/call @!view :destroy)))))

(def clj-editor (editor {:extensions clj-extensions}))

(def sql-editor (editor {:extensions sql-extensions}))
