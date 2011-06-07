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

(defn index-dir [bot] (fs-directory (str "index-" (.getNick bot))))

(def *chat-log* (atom []))

(def *analyzer*
  (selective-analyzer
   (stemmer-analyzer (standard-analyzer))
   #{"message"}))

(def *msg-max-hits* 3)

(def *prv-msg-max-hits* 5)

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
        [total hits] (search index-searcher query filter max-hits "timestamp")]
    (println "Query:" query)
    (println "Filter:" filter)
    (println ">>" total "hits for query:" query-str)
    (vector
     total
     (map
      #(let [timestamp (-> % :doc :timestamp (Long/parseLong))
             delta (floor (/ (- (System/currentTimeMillis) timestamp) 1000))]
         (format
          "[%s] %s: %s"
          (fuzzy-relative-time delta)
          (-> % :doc :user)
          (-> % :doc :message)))
      hits))))

(defn schedule-index-chat-log [bot]
  (let [executor (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay
     executor
     (fn []
       (try
        (with-open [iw (index-writer (index-dir bot) *analyzer*)]
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
        channel (.getChannel ev)
        query (trim (subs msg 2))]
    (if (.startsWith msg "!q")
      (with-open [is (index-searcher (index-dir bot))]
        (let [[total results]
              (search-chat-log is query *msg-max-hits* *analyzer*)]
          (if (zero? total)
            (send-message bot channel (format "No results found for \"%s\"" query))
            (do
              (send-message
               bot channel
               (format "%s results found for \"%s\". Top %s results:"
                       total query (count results)))
              (doseq [result results]
                (send-message bot channel result))))))
      (when (and (not (.startsWith msg "!")) (not (*ignored-users* user)))
        (swap! *chat-log* conj [timestamp user msg])))))

(defmethod event-listener :private-message [^PircBotX bot ^Event ev]
  (let [msg (trim (.getMessage ev))
        user (.. ev getUser getNick)
        timestamp (.getTimestamp ev)
        query (trim (subs msg 2))]
    (when (.startsWith msg "!q")
      (with-open [is (index-searcher (index-dir bot))]
        (let [[total results]
              (search-chat-log is query *prv-msg-max-hits* *analyzer*)]
          (if (zero? total)
            (send-prv-message bot user (format "No results found for \"%s\"" query))
            (do
              (send-prv-message
               bot user
               (format "%s results found for \"%s\". Top %s results:"
                       total query (count results)))
              (doseq [result results]
                (send-prv-message bot user result)))))))))

(defn run-bot [bot-name server channel]
  (let [bot (make-bot bot-name)]
    (connect-bot bot server channel)
    (schedule-index-chat-log bot)
    bot))
