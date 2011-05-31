(ns irc-search-bot.util
  (:use [clojure.contrib.math :only (floor)]))

(let [second 1
      minute (* 60 second)
      hour (* 60 minute)
      day (* 24 hour)
      month (* 30 day)
      year (* 365 day)]
  (defn fuzzy-relative-time [delta]
    (cond
     (< delta 0)
       "not yet"
     (< delta (* 1 minute))
       (if (== delta 1) "one second ago" (str delta " seconds ago"))
     (< delta (* 2 minute))
       "a minute ago"
     (< delta (* 45 minute))
       (str (floor (/ delta minute)) " minutes ago")
     (< delta (* 90 minute))
       "an hour ago"
     (< delta (* 24 hour))
       (str (floor (/ delta hour)) " hours ago")
     (< delta (* 48 hour))
       "yesterday"
     (< delta (* 30 day))
       (str (floor (/ delta day)) " days ago")
     (< delta (* 12 month))
       (let [months (floor (/ delta month))]
         (if (<= months 1) "one month ago" (str months " months ago")))
     :else
       (let [years (floor (/ delta year))]
         (if (<= years 1) "one year ago" (str years " years ago"))))))