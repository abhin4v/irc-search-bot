(ns irc-search-bot.core
  (:import [java.util.concurrent Executors TimeUnit]
           [java.util Date])
  (:use [irc-search-bot.bot]
        [irc-search-bot.lucene]
        [clojure.string :only (trim join)]
        [clojure.java.io :only (reader as-file)]))

(def *index-dir* (fs-directory "index"))

(def *chat-log* (atom []))

(def *analyzer* (stemmer-analyzer))

(def *max-hits* 3)

(def *ignored-users*
  (if (.exists (as-file "ignored_users"))
    (with-open [rdr (reader "ignored_users")]
      (into (hash-set) (line-seq rdr)))
    #{}))

(defn index-chat-log [index-writer chat-log]
  (doseq [[timestamp user message] chat-log]
    (do
      (println (format "[%tr] %s: %s" (Date. timestamp) user message))
      (add-document
       index-writer
       (document
        (field :timestamp (str timestamp) :index :not-analyzed)
        (field :user user :index :not-analyzed)
        (field :message message))))))

(defn search-chat-log [index-searcher query max-hits analyzer]
  (let [qp (query-parser :message analyzer)
        q (parse-query qp query)
        hits (search index-searcher q max-hits)]
    (println ">>" (count hits) "hits for query:" query)
    (map
     #(let [timestamp (-> % :doc :timestamp (Long/parseLong) (Date.))]
        (format
         "[%tI:%tM %tp] %s: %s"
         timestamp timestamp timestamp
         (-> % :doc :user)
         (-> % :doc :message)))
     hits)))

(defn schedule-index-chat-log []
  (let [executor (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay
     executor
     (fn []
       (try
        (with-open [iw (index-writer *index-dir* *analyzer*)]
          (let [chat-log @*chat-log*]
            (do
              (reset! *chat-log* [])
              (index-chat-log iw chat-log))))
        (catch Exception e
          (.printStackTrace e))))
     10 10 TimeUnit/SECONDS)))

(defmethod event-listener :disconnect [bot ev]
  (do
    (.connect bot (.getServer bot))
    (doseq [channel (.getChannelNames bot)]
      (.joinChannel bot channel))))

(defmethod event-listener :kick [bot ev]
  (join-channel bot (.getChannel ev)))

(defmethod event-listener :message [bot ev]
  (let [msg (trim (.getMessage ev))
        user (.. ev getUser getNick)
        timestamp (.getTimestamp ev)
        channel (.getChannel ev)]
    (if (.startsWith msg "!q")
      (with-open [is (index-searcher *index-dir*)]
        (let [results (search-chat-log is (trim (subs msg 2)) *max-hits* *analyzer*)]
          (if (zero? (count results))
            (send-message bot channel "No results found")
            (doseq [result results]
              (send-message bot channel result)))))
      (when-not (and (.startsWith msg "!") (not (*ignored-users* user)))
        (swap! *chat-log* conj [timestamp user msg])))))

(defn run-bot [bot-name server channel]
  (let [bot (make-bot bot-name)]
    (connect-bot bot server channel)
    (schedule-index-chat-log)))