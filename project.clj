(defproject cauda "0.1.0-SNAPSHOT"
  :description "Queueing thing."
  :url "http://overtherainbow.com"
  :license {:name "None"
            :url "nowhere"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [liberator "0.10.0"]
                 [compojure "1.1.3"]
                 [ring/ring-core "1.2.1"]
                 [ring/ring-jetty-adapter "1.1.0"]
                 [org.clojure/data.json "0.2.4"]
                 [clj-http "0.9.1"]]
  :main cauda.core)
