(ns server
  (:require [clojure.edn :as edn]
            [clojure.instant :refer [read-instant-date]]
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
            [ring.middleware.cors :refer [wrap-cors]]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [hiccup.page :as h]))

(s/def ::system-time (s/nilable string?))
(s/def ::txs string?)
(s/def ::tx-batches (s/coll-of (s/keys :req-un [::system-time ::txs])))
(s/def ::query string?)
(s/def ::db-run (s/keys :req-un [::tx-batches ::query]))

(defn- handle-client-error [ex _]
  {:status 400
   :body {:message (ex-message ex)
          :exception (.getClass ex)
          :data (ex-data ex)}})

(defn- handle-other-error [ex _]
  {:status 500
   :body {:message (ex-message ex)
          :exception (.getClass ex)
          :data (ex-data ex)}})

(def exception-middleware
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {xtdb.IllegalArgumentException handle-client-error
     xtdb.RuntimeException handle-client-error
     clojure.lang.ExceptionInfo handle-client-error
     ::exception/default handle-other-error})))

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
    [:script {:async true
              :defer true
              :data-website-id "aabeabcb-ad76-47a4-9b4b-bef3fdc39af4"
              :src "https://bunseki.juxt.pro/umami.js"}]
    [:title "XT Fiddle"]]
   [:body
    [:div {:id "app"}]
    [:script {:type "text/javascript" :src "/public/js/compiled/app.js"}]
    [:script {:type "text/javascript"}
     (str "var xt_version = '" xt-version "';")]
    [:script {:type "text/javascript"}
     "xt_play.app.init()"]]))


(comment
  (with-open [node (xtn/start-node {})]
    (doseq [st [#inst "2022" #inst "2021"]]
      (let [tx (xt/submit-tx node [] {:system-time st})
            results (xt/q node '(from :xt/txs [{:xt/id $tx-id} xt/error])
                          {:basis {:at-tx tx}
                           :args {:tx-id (:tx-id tx)}})]
        (when-let [error (-> results first :xt/error)]
          (throw (ex-info "Transaction error" {:error error})))))))

(defn run-handler [request]
  (let [{:keys [tx-batches query]} (get-in request [:parameters :body])
        ;; TODO: Filter for only the readers required?
        read-edn (partial edn/read-string {:readers *data-readers*})
        tx-batches (->> tx-batches
                        (map #(update % :system-time (fn [s] (when s (read-instant-date s)))))
                        (map #(update % :txs read-edn)))
        query (read-edn query)]
    (try
      (with-open [node (xtn/start-node {})]
        ;; Run transactions
        (doseq [{:keys [system-time txs]} tx-batches]
          (let [tx (xt/submit-tx node txs {:system-time system-time})
                results (xt/q node '(from :xt/txs [{:xt/id $tx-id} xt/error])
                              {:args {:tx-id (:tx-id tx)}})]
            ;; If any transaction fails, throw the error
            (when-let [error (-> results first :xt/error)]
              (throw error))))
        ;; Run query
        (let [res (xt/q node query (when (string? query)
                                     {:key-fn :snake-case-string}))]
          {:status 200
           :body res}))
      (catch Exception e
        (log/warn :submit-error {:e e})
        (throw e)))))

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
             :handler #'run-handler}}]

    ["/public/*" (ring/create-resource-handler)]]
   {:exception pretty/exception
    :data {:coercion rcs/coercion
           :muuntaja m/instance
           :middleware [#(wrap-cors %
                                    :access-control-allow-origin #".*"
                                    :access-control-allow-methods [:get :put :post :delete])
                        params/wrap-params
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
