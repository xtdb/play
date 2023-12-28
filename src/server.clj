(ns server
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [hiccup.core :as hiccup]
            [muuntaja.core :as m]
            [reitit.dev.pretty :as pretty]
            [reitit.ring :as ring]
            [reitit.coercion.spec :as rcs]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [ring.middleware.params :as params]
            [ring.adapter.jetty :as jetty]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]))

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

(s/def ::txs (s/or :xtql string? :sql vector?))
(s/def ::query string?)
(s/def ::type string?)
(s/def ::db-run (s/keys :req-un [::txs ::query ::type]))


(defn- handle-ex-info [ex req]
  {:status 400,
   :body {:message (ex-message ex)
          :exception (.getClass ex)
          ;; :data (ex-data exception)
          :uri (:uri req)}})

(def exception-middleware
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {xtdb.IllegalArgumentException handle-ex-info})))

(defn eval-txs [txs]
  (mapv (fn [[_op-symbol table doc]] (xt/put table doc)) txs))

(defn router
  []
  (ring/router
   [["/"
     {:get {:summary "Fetch main page"
            :handler (fn [_request]
                       {:status 200
                        :body home-html})}}]
    ["/db-run"
     {:post {:summary "Run transactions + a query"
             :parameters {:body ::db-run}
             :handler (fn [request]
                        (let [{:keys [txs query type] :as body} (get-in request [:parameters :body])
                              ;; TODO take out eval and double encoding
                              [txs query] (case type
                                            "xtql" [(eval-txs (edn/read-string (edn/read-string txs)))
                                                    (edn/read-string query)]
                                            "sql" [(mapv #(xt/sql-op %) txs)
                                                   query]
                                            (UnsupportedOperationException.))]
                          (prn txs)
                          (try
                            (with-open [node (xtn/start-node {})]
                              (xt/submit-tx node txs)
                              (let [res (xt/q node query)]
                                {:status 200
                                 :body res}))
                            (catch Exception e
                              (log/warn :submit-error {:e e})
                              {:status 400
                               :body e}))))}}]]
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
  (do
    (.stop server)
    (def server (start {:join false})))

  (require '[clojure.java.browse :as browse])
  (browse/browse-url "http://localhost:8000")

  ;; testing the db-run route
  (require '[hato.client :as client])

  ;; XTQL
  (def txs (pr-str "[(xt/put :docs {:xt/id 1 :foo \"bar\"})]"))
  (def query (pr-str '(from :docs [xt/id foo])))

  (-> (client/request {:accept :json
                       :as :string
                       :request-method :post
                       :content-type :json
                       :form-params {:txs txs :query query :type "xtql"}
                       :url "http://localhost:8000/db-run"
                       :throw-exceptions? false} {}
                      )
      :body)
  ;; => "[{\"foo\":\"bar\",\"xt/id\":1}]"

  ;; SQL
  (def txs ["INSERT INTO users (xt$id, name) VALUES ('jms', 'James'), ('hak', 'Håkan')"])
  (def query "SELECT * FROM users")

  (-> (client/request {:accept :json
                       :as :string
                       :request-method :post
                       :content-type :json
                       :form-params {:txs txs :query query :type "sql"}
                       :url "http://localhost:8000/db-run"
                       :throw-exceptions? false} {}
                      )
      :body)

  )
