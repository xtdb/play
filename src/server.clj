(ns server
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.core.match :refer [match]]
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
            [xtdb.error :as err]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]))

(s/def ::txs string?) ; Always EDN
(s/def ::query string?) ; Either XTQL or SQL
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

(defn router
  []
  (ring/router
   [["/"
     {:get {:summary "Fetch main page"
            :handler (fn [_request]
                       (-> (response/resource-response "public/index.html")
                           (response/content-type "text/html")))}}]

    ["/status"
     {:get {:summary "Check server status"
            :handler (fn [_request]
                       (response/response {:status "ok"}))}}]

    ["/db-run"
     {:post {:summary "Run transactions + a query"
             :parameters {:body ::db-run}
             :handler (fn [request]
                        (let [{:keys [txs query] :as body} (get-in request [:parameters :body])
                              ;; TODO: Filter for only the reader required?
                              txs (edn/read-string {:readers *data-readers*} txs)
                              query (edn/read-string {:readers *data-readers*} query)]
                          #_(log/info :requst-data {:txs txs :query query})
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
                       :form-params {:txs txs :query query}
                       :url "http://localhost:8000/db-run"
                       :throw-exceptions? false} {})
      :body)
  ;; => "[{\"foo\":\"bar\",\"xt/id\":1}]"

  ;; SQL
  (def txs ["INSERT INTO users (xt$id, name) VALUES ('jms', 'James'), ('hak', 'Håkan')"])
  (def query "SELECT * FROM users")

  (-> (client/request {:accept :json
                       :as :string
                       :request-method :post
                       :content-type :json
                       :form-params {:txs txs :query query}
                       :url "http://localhost:8000/db-run"
                       :throw-exceptions? false} {})
      :body))


