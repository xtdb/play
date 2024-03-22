(ns xt-fiddle.editor
  (:require ["@codemirror/autocomplete" :refer [autocompletion]]
            ["@codemirror/commands" :refer [defaultKeymap history historyKeymap indentWithTab]]
            ["@codemirror/language" :refer [foldGutter syntaxHighlighting defaultHighlightStyle]]
            ["@codemirror/lang-sql" :refer [sql PostgreSQL]]
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

(defn js-concat [& colls]
  (apply array (apply concat colls)))

(defonce clj-extensions
  #js [theme
       (history)
       (view/drawSelection)
       (syntaxHighlighting defaultHighlightStyle)
       (foldGutter)
       (lineNumbers)
       (.. EditorState -allowMultipleSelections (of true))
       cm-clj/default-extensions
       (.of view/keymap (js-concat cm-clj/complete-keymap historyKeymap))])

(def sql-extensions
  #js [theme
       (history)
       (view/drawSelection)
       (syntaxHighlighting defaultHighlightStyle #js{:fallback true})
       (foldGutter)
       (lineNumbers)
       (.. EditorState -allowMultipleSelections (of true))
       (.of view/keymap (js-concat defaultKeymap historyKeymap [indentWithTab]))
       ;; IMO too annoying to use
       #_(autocompletion)
       (sql #js{:dialect PostgreSQL
                #_#_:schema #js{"xt$txs" #js["xt$id" "xt$committed?"
                                             "xt$error" "xt$tx_time"
                                             "xt$valid_from" "xt$valid_to"
                                             "xt$system_from" "xt$system_to"]}
                :upperCaseKeywords true})])

(defn make-view [{:keys [state parent]}]
  (new EditorView #js{:state state :parent parent}))

(defn make-state [{:keys [doc extensions]}]
  (.create EditorState #js{:doc doc :extensions (clj->js extensions)}))

;; NOTE: There's a bug here: changes to `source` aren't represented in the editor.
;;       I can't think of a way around this right now :/
;;       I'm going to "fix" it by trying to make sure this doesn't happen
(defn editor [{:keys [extensions]}]
  (fn [{:keys [source change-callback] my-class :class}]
    (r/with-let [!view (r/atom nil)
                 ; NOTE: This must be defined in the with-let
                 ;       If put under :ref then it's called every time
                 ;       the component is re-rendered.
                 ;       This would create multiple instances of the editor.
                 mount! (fn [el]
                          (when el
                            (let [extensions [extensions
                                              (on-change change-callback)]
                                  state (make-state
                                         {:doc source
                                          :extensions extensions})]
                              (reset! !view (make-view
                                             {:parent el
                                              :state state})))))]
      [:div {:class my-class
             :ref mount!}]
      (finally
        (j/call @!view :destroy)))))

(def clj-editor (editor {:extensions clj-extensions}))

(def sql-editor (editor {:extensions sql-extensions}))
