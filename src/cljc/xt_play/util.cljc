(ns xt-play.util
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.string :as str]
                    [clojure.data.json :as json])
     :cljs (:require [clojure.string :as str])))

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
        [;; java.util.Date (returned by JDBC as #inst)
         (instance? java.util.Date v) (str "TIMESTAMP '" (.toInstant v) "'")
         ;; java.time.* types
         (instance? java.time.LocalDate v) (str "DATE '" v "'")
         (instance? java.time.LocalTime v) (str "TIME '" v "'")
         (instance? java.time.LocalDateTime v) (str "TIMESTAMP '" v "'")
         (instance? java.time.OffsetDateTime v) (str "TIMESTAMP '" v "'")
         (instance? java.time.ZonedDateTime v) (str "TIMESTAMP '" v "'")
         (instance? java.time.Instant v) (str "TIMESTAMP '" v "'")]
        :cljs
        [(instance? js/Date v) (str "TIMESTAMP '" (.toISOString v) "'")])

    (map? v) (str "{"
                  (str/join ", "
                            (map (fn [[k v]]
                                   (str (if (keyword? k) (name k) k)
                                        ": "
                                        (sql-pr-str v)))
                                 v))
                  "}")
    (vector? v) (str "[" (str/join ", " (map sql-pr-str v)) "]")
    (set? v) (str "#{" (str/join ", " (map sql-pr-str v)) "}")
    :else (pr-str v)))

#?(:clj
   (defn- looks-like-timestamp?
     "Checks if a string looks like an ISO timestamp"
     [s]
     (and (string? s)
          (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:\d{2})?" s))))

(defn transform-dates-to-sql
  "Recursively walks through data structures and converts date/time objects to SQL literal strings"
  [v]
  #?(:clj
     (cond
       ;; PGobject (PostgreSQL JSON/JSONB objects)
       (instance? org.postgresql.util.PGobject v)
       (let [json-str (.getValue v)
             parsed (json/read-str json-str :key-fn keyword)]
         ;; Recursively transform any dates in the parsed JSON
         (transform-dates-to-sql parsed))

       ;; Timestamp-like strings from JSON - convert to SQL literals
       (looks-like-timestamp? v) (str "TIMESTAMP '" v "'")

       ;; java.util.Date (returned by JDBC as #inst)
       (instance? java.util.Date v) (str "TIMESTAMP '" (.toInstant v) "'")
       ;; java.time.* types
       (instance? java.time.LocalDate v) (str "DATE '" v "'")
       (instance? java.time.LocalTime v) (str "TIME '" v "'")
       (instance? java.time.LocalDateTime v) (str "TIMESTAMP '" v "'")
       (instance? java.time.OffsetDateTime v) (str "TIMESTAMP '" v "'")
       (instance? java.time.ZonedDateTime v) (str "TIMESTAMP '" v "'")
       (instance? java.time.Instant v) (str "TIMESTAMP '" v "'")
       ;; Recursive cases
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
