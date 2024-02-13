(ns user
  (:require [server]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as repl]
            [integrant.core :as ig]
            [integrant.repl :as igr :refer [go halt]]
            [lambdaisland.classpath.watch-deps :as watch-deps]))

(defn browse [port]
  (browse/browse-url (str "http://localhost:" port)))

(defn watch-deps!
  []
  (watch-deps/start! {:aliases [:dev :test]}))


(integrant.repl/set-prep! #(ig/prep {:server/server {:join false :port 8000}}))

(def go! go)

(comment
  (repl/set-refresh-dirs (io/file "src") (io/file "dev"))
  (repl/refresh)
  (repl/clear)

  (watch-deps!)

  (go)
  (halt)
  (browse 8000))
