(ns irc-search-bot.lucene
  (:import [org.apache.lucene.document Document Field Field$Store Field$Index]
           [org.apache.lucene.store RAMDirectory FSDirectory]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.util Version]
           [org.apache.lucene.index IndexWriter IndexWriterConfig IndexReader]
           [org.apache.lucene.search
            IndexSearcher ScoreDoc Query BooleanQuery TermQuery BooleanClause$Occur
            Filter QueryWrapperFilter]
           [org.apache.lucene.queryParser QueryParser]
           [org.apache.lucene.wordnet AnalyzerUtil])
  (:use [clojure.java.io :only (as-file)]))

(def *lucene-version* Version/LUCENE_30)

(defn index-writer [directory analyzer]
  (IndexWriter. directory (IndexWriterConfig. *lucene-version* analyzer)))

(defn index-searcher [directory]
  (IndexSearcher. (IndexReader/open directory)))

(defn query-parser [default-field-name analyzer]
  (QueryParser. *lucene-version* (name default-field-name) analyzer))

(defn parse-query [^QueryParser query-parser query-text]
  (.parse query-parser query-text))

(defn filterify-query [^Query query must-fields]
  (if (instance? BooleanQuery query)
    (let [new-query (BooleanQuery.)
          filter-query (BooleanQuery.)]
      (doseq [clause (.clauses query)]
        (let [subquery (.getQuery clause)]
          (if (and (instance? TermQuery subquery)
                   (must-fields (.field (.getTerm subquery))))
            (do
              (.setOccur clause BooleanClause$Occur/MUST)
              (.add filter-query clause))
            (do
              (.add new-query clause)))))
      [new-query (QueryWrapperFilter. filter-query)])
    [query, nil]))

(defn search [^IndexSearcher index-searcher ^Query query ^Filter filter ^Integer max-hits]
  (->>
   (if (nil? filter)
     (.search index-searcher query max-hits)
     (.search index-searcher query filter max-hits))
   (.scoreDocs)
   seq
   (map
    (fn [^ScoreDoc sd]
      (hash-map
       :score (.score sd)
       :doc
       (->>
        (.doc index-searcher (.doc sd))
        (.getFields)
        seq
        (reduce
         (fn [m ^Field f]
           (assoc m
             (keyword (.name f))
             (if (.isBinary f) (.getBinaryValue f) (.stringValue f))))
         {})))))))

(defn fs-directory [dir-path]
  (FSDirectory/open (as-file dir-path)))

(defn ram-directory []
  (RAMDirectory.))

(def index-vals
  {:no Field$Index/NO
   :analyzed Field$Index/ANALYZED
   :not-analyzed Field$Index/NOT_ANALYZED
   :not-analyzed-no-norms Field$Index/NOT_ANALYZED_NO_NORMS
   :analyzed-no-norms Field$Index/ANALYZED_NO_NORMS})

(defn field
  [field-name ^String field-value & {:keys [store index] :or {store :yes index :analyzed}}]
  (Field.
   (name field-name)
   field-value
   (if (= store :yes) Field$Store/YES Field$Store/NO)
   ^Field$Index (index-vals index)))

(defn document [& fields]
  (let [d (Document.)]
    (doseq [f fields]
      (.add d f))
    d))

(defn add-document [^IndexWriter index-writer document]
  (.addDocument index-writer document))
  
(defn standard-analyzer []
  (StandardAnalyzer. *lucene-version*))

(defn stemmer-analyzer []
  (AnalyzerUtil/getPorterStemmerAnalyzer (standard-analyzer)))
