(ns irc-search-bot.bot
  (:import [org.pircbotx PircBotX Channel]
           [org.pircbotx.hooks Event Listener])
  (:use [clojure.string :only [join lower-case]]))

(defn spy [o] (do (println o) o))

(defmulti event-listener
  (fn [^PircBotX bot ^Event event]
    (->>
     event
     class
     (.getSimpleName)
     ;(spy)
     (re-seq #"([A-Z][^A-Z]*)")
     butlast
     (map first)
     (map lower-case)
     (join "-")
     keyword)))

(defn make-bot [name]
  (doto (PircBotX.)
    (.setName name)
    (.setLogin name)
    (..
     (getListenerManager)
     (addListener
      (proxy [Listener] []
        (onEvent [^Event e]
          (try
            (event-listener (.getBot e) e)
            (catch Exception e
              (.printStackTrace e)))))))))

(defmethod event-listener :default [bot ev])

(defn connect-bot [^PircBotX bot server channel]
  (doto bot
    (.connect server)
    (.joinChannel channel)))

(defn disconnect-bot [^PircBotX bot]
  (doto bot (.disconnect)))

(defn join-channel [^PircBotX bot channel]
  (doto bot
    (.joinChannel
     (if (instance? Channel channel)
       (.getName ^Channel channel)
       channel))))

(defn send-message [^PircBotX bot channel message]
  (doto bot
    (.sendMessage
     (if (instance? Channel channel)
       channel
       (.getChannel bot channel))
     message)))