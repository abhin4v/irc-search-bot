(defproject irc-search-bot "1.0.0-SNAPSHOT"
  :description "An IRC bot to search the IRC chat history"
  :repositories {"general-maven-repo-snapshot"
                 {:url "http://general-maven-repo.googlecode.com/svn/maven2/snapshots"
                  :snapshots true
                  :releases false}}
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.apache.lucene/lucene-core "3.1.0"]
                 [org.apache.lucene/lucene-wordnet "3.1.0"]
                 [org/pircbotx "1.3-SNAPSHOT"]])
