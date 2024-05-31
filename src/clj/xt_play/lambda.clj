(ns xt-play.lambda
  (:gen-class
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]
   :init init)
  (:require [muuntaja.core :as m]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [xt-play.base64 :as b64]
            [xt-play.handler :as h]))

(defn -init []
  ; NOTE: This ensure xtdb is warmed up before starting the server
  ;       Otherwise, the first few requests will time out
  (with-open [node (xtn/start-node {})]
    (xt/status node))
  [[] nil])

; https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html#urls-request-payload
(defn ->ring-request [request]
  (let [http (get-in request ["requestContext" "http"])
        headers (get request "headers")]
    (merge
      (when-let [body (get request "body")]
        {:body (if (get request "isBase64Encoded")
                 (b64/decode body)
                 body)})
      {:headers headers ; NOTE: already lower-case
       :protocol (get http "protocol")
       :query-string (get request "rawQueryString")
       :remote-addr (get http "sourceIp")
       :request-method (-> (get http "method") (str/lower-case) keyword)
       :scheme (keyword (get headers "x-forwarded-proto"))
       :server-name (get-in request ["requestContext" "domainName"])
       :server-port (get headers "x-forwarded-port")
       :uri (get http "path")})))

; Source: https://sideshowcoder.com/2018/05/11/clojure-ring-api-gateway-lambda/
(defmulti wrap-body class)
(defmethod wrap-body String [body] body)
(defmethod wrap-body clojure.lang.ISeq [body] (str/join body))
(defmethod wrap-body java.io.File [body] (slurp body))
(defmethod wrap-body java.io.InputStream [body] (slurp body))

; https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html#urls-response-payload
(defn ring-response-> [response]
  {:statusCode (:status response)
   :headers (:headers response)
   :isBase64Encoded true
   :body (-> response :body wrap-body b64/encode)})

(def m
  (m/create
    (assoc-in
      m/default-options
      [:formats "application/json" :decoder-opts]
      {:decode-key-fn identity})))

(defn -handleRequest [_ is os _context]
  (let [res (->> is
                 (m/decode m "application/json")
                 ->ring-request
                 h/handler
                 ring-response->
                 (m/encode m "application/json"))]
    (io/copy res os)))
