(ns irc-search-bot.core
  (:import [java.util.concurrent Executors TimeUnit]
           [java.util Date]
           [org.pircbotx PircBotX Channel]
           [org.pircbotx.hooks Event])
  (:use [irc-search-bot.bot]
        [irc-search-bot.lucene]
        [irc-search-bot.util]
        [clojure.string :only (trim join)]
        [clojure.java.io :only (reader as-file)]
        [clojure.contrib.math :only (floor)]))

(def *index-dir* (fs-directory "index"))

(def *chat-log* (atom []))

(def *analyzer*
  (selective-analyzer
   (stemmer-analyzer (standard-analyzer))
   #{"message"}))

(def *max-hits* 3)

(def *ignored-users*
  (if (.exists (as-file "ignored_users"))
    (with-open [rdr (reader "ignored_users")]
      (into (hash-set) (line-seq rdr)))
    #{}))

(defn index-chat-log [index-writer chat-log]
  (doseq [[^Long timestamp user message] chat-log]
    (do
      (println (format "[%tr] %s: %s" (Date. timestamp) user message))
      (add-document
       index-writer
       (document
        (field :timestamp (str timestamp) :index :not-analyzed)
        (field :user user :index :not-analyzed)
        (field :message message))))))
  
(defn search-chat-log [index-searcher query-str max-hits analyzer]
  (let [qp (query-parser :message analyzer)
        raw-query (parse-query qp query-str)
        [query filter] (filterify-query raw-query #{"user"})
        hits (search index-searcher query filter max-hits)]
    (println "Query:" query)
    (println "Filter:" filter)
    (println ">>" (count hits) "hits for query:" query-str)
    (map
     #(let [timestamp (-> % :doc :timestamp (Long/parseLong))
            delta (floor (/ (- (System/currentTimeMillis) timestamp) 1000))]
        (format
         "[%s] %s: %s"
         (fuzzy-relative-time delta)
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

(defmethod event-listener :disconnect [^PircBotX bot ^Event ev]
  (do
    (.connect bot (.getServer bot))
    (doseq [channel (.getChannelNames bot)]
      (.joinChannel bot channel))))

(defmethod event-listener :kick [^PircBotX bot ^Event ev]
  (join-channel bot (.getChannel ev)))

(defmethod event-listener :message [^PircBotX bot ^Event ev]
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
      (when (and (not (.startsWith msg "!")) (not (*ignored-users* user)))
        (swap! *chat-log* conj [timestamp user msg])))))

(defn run-bot [bot-name server channel]
  (let [bot (make-bot bot-name)]
    (connect-bot bot server channel)
    (schedule-index-chat-log)
    bot))
