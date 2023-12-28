(ns xt-fiddle.editor
  (:require ["@codemirror/commands" :refer [history historyKeymap]]
            ["@codemirror/language" :refer [foldGutter syntaxHighlighting defaultHighlightStyle]]
            ["@codemirror/lang-javascript" :refer [javascript]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :as view :refer [EditorView]]
            ["react" :as react]
            [applied-science.js-interop :as j]
            [clojure.string :as str]
            [nextjournal.clojure-mode :as cm-clj]
            [nextjournal.clojure-mode.test-utils :as test-utils]
            [nextjournal.livedoc :as livedoc]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [shadow.resource :as rc]))

(def theme
  (.theme EditorView
          (j/lit {".cm-content" {:white-space "pre-wrap"
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
                                 :border "none"}
                  ".cm-gutterElement" {:margin-left "5px"}
                  ;; only show cursor when focused
                  ".cm-cursor" {:visibility "hidden"}
                  "&.cm-focused .cm-cursor" {:visibility "visible"}})))

(defn on-change [callback]
  (view/EditorView.updateListener.of (fn [update]
                                       (when (.-changes update)
                                         (callback (.. update -state -doc -toString))))))


(defonce extensions #js [theme
                         (history)
                         (syntaxHighlighting defaultHighlightStyle)
                         (view/drawSelection)
                         (foldGutter)
                         (.. EditorState -allowMultipleSelections (of true))
                         cm-clj/default-extensions
                         (.of view/keymap cm-clj/complete-keymap)
                         (.of view/keymap historyKeymap)])


(defn editor [source {:keys [change-callback]}]
  (r/with-let [!view (r/atom nil)
               mount! (fn [el]
                        (when el
                          (reset! !view (new EditorView
                                             (j/obj :state
                                                    (test-utils/make-state #js [extensions (on-change change-callback)] source)
                                                    :parent el)))))]
    [:div
     [:div {:class "rounded-md mb-0 text-sm monospace overflow-auto relative border shadow-lg bg-white"
            :ref mount!
            :style {:max-height 410}}]]
    (finally
      (j/call @!view :destroy))))
