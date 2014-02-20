(ns ighutil.subcommands.distinct-by-vdjcdr3
  (:import [net.sf.samtools
            SAMRecord
            SAMFileReader
            SAMFileReader$ValidationStringency
            SAMFileWriterFactory
            SAMFileHeader$SortOrder])
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [cliopatra.command :refer [defcommand]]
            [ighutil.imgt :refer [strip-allele]]
            [ighutil.io :as zio]
            [ighutil.sam :as sam]
            [plumbing.core :refer [distinct-by for-map frequencies-fast]]))

(defn- vdjcdr3 [sam-records]
  (let [^SAMRecord record (first sam-records)
        vdj (for-map
             [^String reference (map sam/reference-name sam-records)]
             (.substring reference 0 4) reference)
        cdr3-length (.getIntegerAttribute record "XL")]
    [(vdj "IGHV") (vdj "IGHD") (vdj "IGHJ") cdr3-length]))

(defcommand distinct-by-vdjcdr3
  "Distinct records by V/D/J/cdr3"
  {:opts-spec [["-i" "--in-file" "Source BAM - must be sorted by *name*"
                :required true :parse-fn io/file]
               ["-o" "--out-file" "Destination path" :required true
                :parse-fn io/file]
               ["-c" "--count-file" "Store input counts of each V/D/J/CDR3 combination to this path"
                :parse-fn zio/writer]
               ["--[no-]compress" "Compress BAM output?" :default true]]}
  (assert (not= in-file out-file))
  (assert (.exists ^java.io.File in-file))
  (with-open [reader (SAMFileReader. ^java.io.File in-file)]
    (.setValidationStringency
     reader
     SAMFileReader$ValidationStringency/SILENT)
    (let [header (.getFileHeader reader)
          read-iterator (->> reader
                             .iterator
                             iterator-seq)
          by-name (->> read-iterator
                       (partition-by sam/read-name))
          vdj-freqs (atom {})]
      (.setSortOrder header SAMFileHeader$SortOrder/unsorted)
      (with-open [writer (sam/bam-writer
                          out-file
                          reader
                          :compress compress)]
        (doseq [record-group by-name]
          (let [group (vdjcdr3 record-group)]
            (when (not (contains? @vdj-freqs group))
              ;; New group
              (doseq [^SAMRecord read record-group]
                (.addAlignment writer read)))
            (swap! vdj-freqs update-in [group] (fnil inc 0)))))
      (when count-file
        (with-open [^java.io.Writer f count-file]
          (->> @vdj-freqs
               seq
               sort
               (map #(apply conj %))
               (cons ["v_gene" "d_gene" "j_gene" "cdr3_length" "count"])
               (csv/write-csv count-file)))))))
