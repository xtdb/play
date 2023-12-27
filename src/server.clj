(ns server
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [hiccup.core :as hiccup]
            [muuntaja.core :as m]
            #_[pages
               [home :as home]
               [draw :as draw]
               [history :as history]]
            [reitit.dev.pretty :as pretty]
            [reitit.ring :as ring]
            [reitit.coercion.spec :as rcs]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.params :as params]
            [ring.adapter.jetty :as jetty]
            [xtdb.api :as xt]
            [xtdb.node :as xtn])
  (:import [java.time ZonedDateTime]
           [java.time.format DateTimeFormatter]))


(def home-html
  (hiccup/html
      [:html
       [:head
        [:title "XT fiddle"]
        [:link {:rel "stylesheet" :href "/assets/css/main.css"}]]
       [:body.block
        [:div.container
         [:h1.title "XT fiddle"]
         #_[:a.button {:href "/draw" :target "_blank"} "Create a new easel!"]
         [:h2 "Go nuts"]]]]))

(s/def ::txs string?)
(s/def ::query string?)
(s/def ::db-run (s/keys :req-un [::txs ::query]))


(defn router
  []
  (ring/router
   [["/"
     {:get {:summary "Fetch main page"
            :handler (fn [_request]
                       {:status 200
                        :body home-html})}}]
    ["/db-run"
     {:post {:summary "Run "
             :parameters {:body ::db-run}
             :handler (fn [request]
                        (let [{:keys [txs query]} (get-in request [:parameters :body])]
                          {:status 200
                           :body {:txs txs
                                  :query query}}))}}]]
   {:exception pretty/exception
    :data {:coercion rcs/coercion
           :muuntaja m/instance
           :middleware [params/wrap-params
                        muuntaja/format-middleware
                        rrc/coerce-exceptions-middleware
                        rrc/coerce-request-middleware
                        rrc/coerce-response-middleware]}}))

(defn start
  [{:keys [join]}]
  (let [port 8000
        server (jetty/run-jetty (ring/ring-handler
                                 (router)
                                 (ring/routes
                                  (ring/create-resource-handler {:path "/assets/"})
                                  (ring/create-default-handler)))
                                {:port port, :join? join})]
    (log/info "server running " "on port " port)
    server))

(comment
  (def server (start {:join false}))
  (.stop server)

  (require '[clojure.java.browse :as browse])
  (browse/browse-url "http://localhost:8000")

  ;; testing the db-run route
  (require '[hato.client :as client])

  (-> (client/request {:accept :json
                       :as :string
                       :request-method :post
                       :content-type :json
                       :form-params {:txs "foo" :query "bar"}
                       :url "http://localhost:8000/db-run"} {})
      :body)
  ;; => "{\"txs\":\"foo\",\"query\":\"bar\"}"
  )
