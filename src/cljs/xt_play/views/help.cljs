(ns xt-play.views.help
  (:require ["@heroicons/react/24/outline" :refer [QuestionMarkCircleIcon]]
            [re-frame.core :as rf]
            [xt-play.components.slider :as s]))

(defn- mono [t] [:span.font-mono.bg-gray-100.text-sm.p-1.rounded t])
(defn- link [t [href stay-on-page?]]
  [:a.hover:underline.text-orange-700.font-medium.cursor-pointer
   (merge {:href href}
          (when-not stay-on-page? {:target "_blank"}))
   t])

(defn- subheading [& t]
  [:h2 {:class "pt-2 text-xl font-semibold text-gray-900"}
   t
   [:hr]])

(defn- paragraph [& t]
  [:p {:class "py-2"} t])

(def help-body
  [:div
   [:p {:class "text-lg text-gray-900 my-1"}
    "Welcome to xt-fiddle, a playground for "
    [link "xtdb" ["https://docs.xtdb.com/index.html"]] "."]
   [subheading "Usage"]

   [paragraph
    "Below the navbar you'll see two panels: " [mono "transactions"] " and " [mono "query"] "."]

   [paragraph
    "In the " [mono "transactions"] " panel you can write colon (" [mono ";"] ") separated "
    [link "transactions" ["https://docs.xtdb.com/reference/main/sql/txs"]]
    " that will all be executed at the same "
    [link "system time" ["https://docs.xtdb.com/quickstart/sql-overview.html#system-time-columns-automatic-time-versioning-of-rows-without-audit-tables"]]
    ". To execute transactions at a different or multiple system times then click the " [mono "+"] " button and adjust the date above each panel. "
    "Make sure that each system time is greater or equal to the previous one, just like when you execute transactions in real time!"]

   [paragraph
    "In the " [mono "query"] " panel you can write a single " [link "query" ["https://docs.xtdb.com/reference/main/sql/queries.html"]] [:span.italic " after "] "all the transactions have been run. "]

   [paragraph
    "You can use the " [mono "run"] " button to execute the transactions then run the query."]

   [paragraph
    "Share your current state by clicking the " [mono "copy url button"] " then share the generated url."]

   [subheading "Examples"]
   [paragraph
    [link "Simple insert" ["/?version=2.0.0-beta3&type=sql&txs=W3sidHhzIjoiSU5TRVJUIElOVE8gcGVvcGxlIChfaWQsIG5hbWUpIFZBTFVFUyAoNiwgJ2ZyZWQnKSIsInN5c3RlbS10aW1lIjoiMjAyNC0wMS0wMVQwMDowMDowMC4wMDBaIn1d&query=U0VMRUNUICogRlJPTSBwZW9wbGU%3D" true]]]

   [paragraph
    [link "Nested data" ["?version=2.0.0-beta3&type=sql&txs=W3sidHhzIjoiSU5TRVJUIElOVE8gcGVvcGxlIChfaWQsIG5hbWUsIGxpa2VzKVxuVkFMVUVTICg5LCAnYm9iJywgWydmaXNoaW5nJywgMy4xNCwge25lc3RlZDonZGF0YSd9XSkiLCJzeXN0ZW0tdGltZSI6bnVsbH1d&query=U0VMRUNUICogRlJPTSBwZW9wbGU%3D" true]]]

   [paragraph
    [link "Record syntax" ["/?version=2.0.0-beta3&type=sql&txs=W3sidHhzIjoiSU5TRVJUIElOVE8gcGVvcGxlXG5SRUNPUkRTIHtfaWQ6IDYsXG4gICAgICAgICBuYW1lOiAnZnJlZCcsXG4gICAgICAgICBpbmZvOiB7Y29udGFjdDogW3tsb2M6ICdob21lJyxcbiAgICAgICAgICAgICAgICAgICAgICAgICAgIHRlbDogJzEyMyd9LFxuICAgICAgICAgICAgICAgICAgICAgICAgICB7bG9jOiAnd29yaycsXG4gICAgICAgICAgICAgICAgICAgICAgICAgICB0ZWw6ICc0NTYnLFxuICAgICAgICAgICAgICAgICAgICAgICAgICAgcmVnaXN0ZXJlZDogREFURSAnMjAyNC0wMS0wMSd9XX19Iiwic3lzdGVtLXRpbWUiOm51bGx9XQ%3D%3D&query=U0VMRUNUIChwZW9wbGUuaW5mbykuY29udGFjdFsyXS50ZWwKRlJPTSBwZW9wbGUKV0hFUkUgcGVvcGxlLm5hbWUgPSAnZnJlZCc%3D" true]]]

   [paragraph
    [link "Transaction log" ["/?version=2.0.0-beta3&type=sql&txs=W3sidHhzIjoiSU5TRVJUIElOVE8gcGVvcGxlIChfaWQsIG5hbWUpIFZBTFVFUyAoNiwgJ2ZyZWQnKSIsInN5c3RlbS10aW1lIjoiMjAyNC0wMS0wMVQwMDowMDowMC4wMDBaIn0seyJ0eHMiOiJJTlNFUlQgSU5UTyBwZW9wbGUgKF9pZCwgbmFtZSwgbGlrZXMpXG5WQUxVRVMgKDksICdib2InLCBbJ2Zpc2hpbmcnLCAzLjE0LCB7bmVzdGVkOidkYXRhJ31dKSIsInN5c3RlbS10aW1lIjoiMjAyNC0wMS0wMlQwMDowMDowMC4wMDBaIn0seyJ0eHMiOiJJTlNFUlQgSU5UTyBwZW9wbGVcblJFQ09SRFMge19pZDogNixcbiAgICAgICAgIG5hbWU6ICdmcmVkJyxcbiAgICAgICAgIGluZm86IHtjb250YWN0OiBbe2xvYzogJ2hvbWUnLFxuICAgICAgICAgICAgICAgICAgICAgICAgICAgdGVsOiAnMTIzJ30sXG4gICAgICAgICAgICAgICAgICAgICAgICAgIHtsb2M6ICd3b3JrJyxcbiAgICAgICAgICAgICAgICAgICAgICAgICAgIHRlbDogJzQ1NicsXG4gICAgICAgICAgICAgICAgICAgICAgICAgICByZWdpc3RlcmVkOiBEQVRFICcyMDI0LTAxLTAxJ31dfX0iLCJzeXN0ZW0tdGltZSI6IjIwMjQtMDEtMDNUMDA6MDA6MDAuMDAwWiJ9XQ%3D%3D&query=U0VMRUNUICogRlJPTSB4dC50eHMgT1JERVIgQlkgX2lkIERFU0MgTElNSVQgMjAgLS0gInJlY2VudCB0cmFuc2FjdGlvbnMi" true]]]

   [paragraph
    [link "Time travel" ["/?version=2.0.0-beta3&type=sql&txs=W3sidHhzIjoiSU5TRVJUIElOVE8gcGVvcGxlIChfaWQsIG5hbWUpIFZBTFVFUyAoNiwgJ2ZyZWQnKSIsInN5c3RlbS10aW1lIjoiMjAyNC0wMS0wMVQwMDowMDowMC4wMDBaIn0seyJ0eHMiOiJJTlNFUlQgSU5UTyBwZW9wbGUgKF9pZCwgbmFtZSwgbGlrZXMpXG5WQUxVRVMgKDksICdib2InLCBbJ2Zpc2hpbmcnLCAzLjE0LCB7bmVzdGVkOidkYXRhJ31dKSIsInN5c3RlbS10aW1lIjoiMjAyNC0wMS0wMlQwMDowMDowMC4wMDBaIn0seyJ0eHMiOiJJTlNFUlQgSU5UTyBwZW9wbGVcblJFQ09SRFMge19pZDogNixcbiAgICAgICAgIG5hbWU6ICdmcmVkJyxcbiAgICAgICAgIGluZm86IHtjb250YWN0OiBbe2xvYzogJ2hvbWUnLFxuICAgICAgICAgICAgICAgICAgICAgICAgICAgdGVsOiAnMTIzJ30sXG4gICAgICAgICAgICAgICAgICAgICAgICAgIHtsb2M6ICd3b3JrJyxcbiAgICAgICAgICAgICAgICAgICAgICAgICAgIHRlbDogJzQ1NicsXG4gICAgICAgICAgICAgICAgICAgICAgICAgICByZWdpc3RlcmVkOiBEQVRFICcyMDI0LTAxLTAxJ31dfX0iLCJzeXN0ZW0tdGltZSI6IjIwMjQtMDEtMDNUMDA6MDA6MDAuMDAwWiJ9LHsidHhzIjoiREVMRVRFIEZST00gcGVvcGxlIFdIRVJFIG5hbWUgPSAnZnJlZCciLCJzeXN0ZW0tdGltZSI6IjIwMjQtMDEtMDRUMDA6MDA6MDAuMDAwWiJ9LHsidHhzIjoiSU5TRVJUIElOVE8gcGVvcGxlIChfaWQsIG5hbWUsIGluZm8pXG5TRUxFQ1QgX2lkLCBuYW1lLCBpbmZvXG5GUk9NIHBlb3BsZSBGT1IgQUxMIFNZU1RFTV9USU1FXG5XSEVSRSBfaWQgPSA2XG5PUkRFUiBCWSBfc3lzdGVtX3RvIERFU0NcbkxJTUlUIDEiLCJzeXN0ZW0tdGltZSI6IjIwMjQtMDEtMDVUMDA6MDA6MDAuMDAwWiJ9LHsidHhzIjoiRVJBU0UgRlJPTSBwZW9wbGUgV0hFUkUgX2lkID0gNiIsInN5c3RlbS10aW1lIjoiMjAyNC0wMS0wNlQwMDowMDowMC4wMDBaIn0seyJ0eHMiOiJJTlNFUlQgSU5UTyBwZW9wbGUgKF9pZCwgbmFtZSwgZmF2b3JpdGVfY29sb3IsIF92YWxpZF9mcm9tKVxuVkFMVUVTICgyLCAnY2Fyb2wnLCAnYmx1ZScsIERBVEUgJzIwMjMtMDEtMDEnKSIsInN5c3RlbS10aW1lIjoiMjAyNC0wMS0wN1QwMDowMDowMC4wMDBaIn0seyJ0eHMiOiJJTlNFUlQgSU5UTyBwZW9wbGUgKF9pZCwgbmFtZSwgZmF2b3JpdGVfY29sb3IsIF92YWxpZF9mcm9tKVxuVkFMVUVTICgyLCAnY2Fyb2wnLCAncmVkJywgREFURSAnMjAyMy0wOS0wMScpIiwic3lzdGVtLXRpbWUiOiIyMDI0LTAxLTA4VDAwOjAwOjAwLjAwMFoifV0%3D&query=U0VUVElORyBERUZBVUxUIFZBTElEX1RJTUUgQVMgT0YgREFURSAnMjAyMy0xMC0wMScsCiAgICAgIERFRkFVTFQgU1lTVEVNX1RJTUUgQVMgT0YgREFURSAnMjAyNC0wMS0wOCcKU0VMRUNUIG5hbWUsIGZhdm9yaXRlX2NvbG9yICwgX3ZhbGlkX2Zyb20sIF9zeXN0ZW1fZnJvbSBGUk9NIHBlb3BsZQ%3D%3D" true]]]
   
   [subheading "Docs"]

   [paragraph
    "For more information on xtdb and tutorials, here are the " [link "docs" ["https://docs.xtdb.com/index.html"]] "."]
   
   [paragraph
    "Something not working as you expect with xtdb-play? Open an issue "
    ;; todo - could template a more meaningul issue, including the session link
    [link "here" ["https://github.com/xtdb/xt-fiddle/issues/new?template=Blank+issue"]] "."]

   [paragraph
    "Found a bug in xtdb? Open an issue "
    ;; todo - could template a more meaningul issue, including the session link
    [link "here" ["https://github.com/xtdb/xtdb/issues/new?template=Blank+issue"]] "."]

   ;; todo - get the exact wording before spending time on formatting!
   ])

(defn slider []
  [:<>
   [:div {:class "cursor-pointer"
          :on-click #(rf/dispatch [::s/toggle :help])}
    [:> QuestionMarkCircleIcon {:class ["h-5 w-5"
                                        "hover:text-gray-500"]}]]
   [s/view {:title "help" :body help-body}]])
