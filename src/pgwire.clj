(ns pgwire
  (:require [integrant.core :as ig]
            [xtdb.pgwire.playground :as play])
  (:import [java.lang AutoCloseable]))

(defmethod ig/init-key ::playground [_ opts]
  (play/open-playground opts))

(defmethod ig/halt-key! ::playground [_ ^AutoCloseable playground]
  (.close playground))
