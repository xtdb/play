(ns xt-play.util
  (:require [clojure.string]
            #?(:clj [clojure.edn :as edn])))

#?(:clj
   (def xt-version
     (-> (slurp "deps.edn")
         (edn/read-string)
         (get-in [:deps 'com.xtdb/xtdb-core :mvn/version]))))

#?(:clj
   (def read-edn
     (partial edn/read-string {:readers *data-readers*})))

(defn sql-pr-str
  "Renders a value as SQL literal notation"
  [v]
  (cond
    (nil? v) "nil"

    ;; Check if string is already a SQL literal (DATE '...', TIME '...', TIMESTAMP '...')
    ;; If so, don't add extra quotes
    (and (string? v)
         (re-matches #"(?i)(DATE|TIME|TIMESTAMP)\s+'.*'" v))
    v

    ;; Regular strings - use SQL single quotes instead of Clojure double quotes
    (string? v) (str "'" v "'")

    (number? v) (str v)
    (boolean? v) (str v)
    (keyword? v) (name v)

    ;; Date/time types - render with SQL type annotations
    #?@(:clj
        [(instance? java.time.LocalDate v) (str "DATE '" v "'")
         (instance? java.time.LocalTime v) (str "TIME '" v "'")
         (instance? java.time.LocalDateTime v) (str "TIMESTAMP '" v "'")
         (instance? java.time.OffsetDateTime v) (str "TIMESTAMP '" v "'")
         (instance? java.time.ZonedDateTime v) (str "TIMESTAMP '" v "'")
         (instance? java.time.Instant v) (str "TIMESTAMP '" v "'")]
        :cljs
        [(instance? js/Date v) (str "TIMESTAMP '" (.toISOString v) "'")])

    (map? v) (str "{"
                  (clojure.string/join ", "
                                       (map (fn [[k v]]
                                              (str (if (keyword? k) (name k) k)
                                                   ": "
                                                   (sql-pr-str v)))
                                            v))
                  "}")
    (vector? v) (str "[" (clojure.string/join ", " (map sql-pr-str v)) "]")
    (set? v) (str "#{" (clojure.string/join ", " (map sql-pr-str v)) "}")
    :else (pr-str v)))

(defn- transform-dates-to-sql
  "Recursively walks through data structures and converts date/time objects to SQL literal strings"
  [v]
  #?(:clj
     (cond
       (instance? java.time.LocalDate v) (str "DATE '" v "'")
       (instance? java.time.LocalTime v) (str "TIME '" v "'")
       (instance? java.time.LocalDateTime v) (str "TIMESTAMP '" v "'")
       (instance? java.time.OffsetDateTime v) (str "TIMESTAMP '" v "'")
       (instance? java.time.ZonedDateTime v) (str "TIMESTAMP '" v "'")
       (instance? java.time.Instant v) (str "TIMESTAMP '" v "'")
       (map? v) (into {} (map (fn [[k v]] [k (transform-dates-to-sql v)]) v))
       (vector? v) (mapv transform-dates-to-sql v)
       (seq? v) (map transform-dates-to-sql v)
       :else v)
     :cljs v))

(defn map-results->rows
  [results]
  [(if (seq (:error results))
     results
     {:result
      (if (every? map? results)
        (let [ks (keys (apply merge results))]
          (into [(vec ks)]
                (mapv (fn [row]
                        ;; Transform date objects to SQL literals before sending to client
                        (mapv #(transform-dates-to-sql (get row %)) ks))
                      results)))
        results)})])
