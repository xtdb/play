#!/usr/bin/env bb

;; Script to generate TPC-H dataset and upload to S3
;; Usage: clojure -M:generate-tpch <bucket-name> <scale-factor>
;; Example: clojure -M:generate-tpch xtdb-play-datasets 0.01

(ns generate-tpch
  (:require [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [xtdb.datasets.tpch :as tpch]
            [xtdb.node :as xtn]))

(defn generate-dataset
  "Generates TPC-H dataset at given scale factor and stores in S3 bucket."
  [bucket-name scale-factor]
  (log/info "Generating TPC-H dataset at scale factor" scale-factor "to bucket" bucket-name)

  (let [node-config {:storage
                     {:object-store
                      {:module 'xtdb.s3/s3-object-store
                       :bucket bucket-name
                       :prefix (str "tpch-sf" scale-factor "/")
                       ;; Public bucket - no credentials needed for read
                       ;; Write requires AWS credentials from environment
                       }}
                     :server {:port 5432}}]

    (log/info "Starting XTDB node with config:" node-config)

    (with-open [node (xtn/start-node node-config)]
      (log/info "Node started, generating TPC-H data...")

      ;; Generate TPC-H dataset
      (tpch/submit-tpch! node {:scale-factor scale-factor})

      (log/info "TPC-H data submitted, waiting for ingest...")

      ;; Wait for data to be ingested
      (Thread/sleep 5000)

      ;; Finish chunk to ensure all data is written
      (log/info "Calling finish-chunk to ensure data is persisted...")
      (.finishChunk node)

      (log/info "Dataset generation complete!"))))

(defn -main [& args]
  (when (not= (count args) 2)
    (println "Usage: clojure -M:generate-tpch <bucket-name> <scale-factor>")
    (println "Example: clojure -M:generate-tpch xtdb-play-datasets 0.01")
    (System/exit 1))

  (let [bucket-name (first args)
        scale-factor (Double/parseDouble (second args))]

    (println "=" (repeat 60 "="))
    (println "TPC-H Dataset Generator")
    (println "=" (repeat 60 "="))
    (println "Bucket:" bucket-name)
    (println "Scale Factor:" scale-factor)
    (println)

    (try
      (generate-dataset bucket-name scale-factor)
      (println)
      (println "✓ Success! Dataset is now available in S3:")
      (println "  s3://" bucket-name "/tpch-sf" scale-factor "/")
      (catch Exception e
        (println "✗ Error generating dataset:")
        (println (.getMessage e))
        (.printStackTrace e)
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
