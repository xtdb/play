#!/usr/bin/env bb

;; Script to generate TPC-H dataset and upload to S3
;; Usage: clojure -M:generate-tpch <bucket-name> <scale-factor>
;; Example: clojure -M:generate-tpch xtdb-play-datasets 0.01

(ns generate-tpch
  (:require [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [xtdb.datasets.tpch :as tpch]
            [xtdb.node :as xtn]
            [xtdb.db-catalog :as db-catalog]
            [xtdb.compactor :as compactor])
  (:import [xtdb.aws.s3 S3Configurator]
           [software.amazon.awssdk.regions Region]))

(defn generate-dataset
  "Generates TPC-H dataset at given scale factor and stores in S3 bucket."
  [bucket-name scale-factor region]
  (log/info "Generating TPC-H dataset at scale factor" scale-factor "to bucket" bucket-name "in region" region)

  (let [node-config {:storage [:remote {:object-store [:s3 {:bucket bucket-name
                                                            :prefix (str "tpch-sf" scale-factor "/")
                                                            :configurator (reify S3Configurator
                                                                            (configureClient [_ builder]
                                                                              (.region builder (Region/of region))))}]}]
                     :disk-cache {:path "/tmp/xtdb-tpch-cache"}}]

    (log/info "Starting XTDB node with S3 storage in region:" region)

    (with-open [node (xtn/start-node node-config)]
      (log/info "Node started, generating TPC-H data...")

      ;; Generate TPC-H dataset using DML (INSERT statements)
      (tpch/submit-dml! node scale-factor)

      (log/info "TPC-H data submitted, waiting for ingest...")

      ;; Wait for data to be ingested
      (Thread/sleep 5000)

      ;; Finish block to ensure all data is written
      (log/info "Calling finish-block to ensure data is persisted...")
      (.finishBlock (.getLogProcessor (db-catalog/primary-db node)))

      ;; Compact all data
      (log/info "Starting compaction...")
      (compactor/compact-all! node nil)
      (log/info "Compaction complete")

      (log/info "Dataset generation complete!"))))

(defn -main [& args]
  (when (not= (count args) 3)
    (println "Usage: clojure -M:generate-tpch <bucket-name> <scale-factor> <region>")
    (println "Example: clojure -M:generate-tpch xtdb-play-datasets 0.01 eu-west-1")
    (System/exit 1))

  (let [bucket-name (first args)
        scale-factor (Double/parseDouble (second args))
        region (nth args 2)]

    (println "=" (repeat 60 "="))
    (println "TPC-H Dataset Generator")
    (println "=" (repeat 60 "="))
    (println "Bucket:" bucket-name)
    (println "Scale Factor:" scale-factor)
    (println "Region:" region)
    (println)

    (try
      (generate-dataset bucket-name scale-factor region)
      (println)
      (println "✓ Success! Dataset is now available in S3:")
      (println (str "  s3://" bucket-name "/tpch-sf" scale-factor "/"))
      (catch Exception e
        (println "✗ Error generating dataset:")
        (println (.getMessage e))
        (.printStackTrace e)
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
