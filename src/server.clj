(ns server
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [muuntaja.core :as m]
            [reitit.coercion.spec :as rcs]
            [reitit.dev.pretty :as pretty]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [ring.util.response :as response]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [hiccup.page :as h]))

(s/def ::txs string?)
(s/def ::query string?)
(s/def ::db-run (s/keys :req-un [::txs ::query]))

(defn- handle-ex-info [ex req]
  {:status 400,
   :body {:message (ex-message ex)
          :exception (.getClass ex)
          :data (ex-data ex)
          :uri (:uri req)}})

(def exception-middleware
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {xtdb.IllegalArgumentException handle-ex-info
     xtdb.RuntimeException handle-ex-info})))

(def xt-version
  (-> (slurp "deps.edn")
      (edn/read-string)
      (get-in [:deps 'com.xtdb/xtdb-core :mvn/version])))
(assert (string? xt-version) "xt-version not present")

(def index
  (h/html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:meta {:name "description" :content ""}]
    [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/default.min.css"}]
    [:link {:rel "stylesheet" :type "text/css" :href "/public/css/main.css"}]
    [:script {:src "https://cdn.tailwindcss.com"}]
    [:title "XT Fiddle"]]
   [:body
    [:div {:id "app"}]
    [:script {:type "text/javascript" :src "/public/js/compiled/app.js"}]
    [:script {:type "text/javascript"}
     (str "var xt_version = '" xt-version "';")]
    [:script {:type "text/javascript"}
     "xt_fiddle.client.init()"]]))

(defn router
  []
  (ring/router
   [["/"
     {:get {:summary "Fetch main page"
            :handler (fn [_request]
                       (-> (response/response index)
                           (response/content-type "text/html")))}}]

    ["/status"
     {:get {:summary "Check server status"
            :handler (fn [_request]
                       (response/response {:status "ok"}))}}]

    ["/db-run"
     {:post {:summary "Run transactions + a query"
             :parameters {:body ::db-run}
             :handler (fn [request]
                        (let [{:keys [txs query]} (get-in request [:parameters :body])
                              ;; TODO: Filter for only the reader required?
                              txs (edn/read-string {:readers *data-readers*} txs)
                              query (edn/read-string {:readers *data-readers*} query)]
                          (try
                            (with-open [node (xtn/start-node {})]
                              (xt/submit-tx node txs)
                              (let [res (xt/q node query)]
                                {:status 200
                                 :body res}))
                            (catch Exception e
                              (log/warn :submit-error {:e e})
                              (throw e)))))}}]

    ["/public/*" (ring/create-resource-handler)]]
   {:exception pretty/exception
    :data {:coercion rcs/coercion
           :muuntaja m/instance
           :middleware [params/wrap-params
                        muuntaja/format-middleware
                        exception-middleware
                        rrc/coerce-exceptions-middleware
                        rrc/coerce-request-middleware
                        rrc/coerce-response-middleware]}}))

(defn start
  [{:keys [join port] :or {port 8000}}]
  ; NOTE: This ensure xtdb is warmed up before starting the server
  ;       Otherwise, the first few requests will fail
  (with-open [node (xtn/start-node {})]
    (xt/status node))
  (let [server (jetty/run-jetty (ring/ring-handler
                                 (router)
                                 (ring/routes
                                  #_(ring/create-resource-handler {:root "public"})
                                  (ring/create-default-handler)))
                                {:port port, :join? join})]
    (log/info "server running on port" port)
    server))

(defmethod ig/init-key ::server [_ opts]
  (start opts))

(defmethod ig/halt-key! ::server [_ server]
  (.stop server))
