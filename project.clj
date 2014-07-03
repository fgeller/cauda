(defproject cauda "0.1.0-SNAPSHOT"
  :description "Queueing thing."
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.0"]
                 [clj-logging-config "1.9.10"]
                 [liberator "0.10.0"]
                 [compojure "1.1.3"]
                 [ring "1.2.1"]
                 [ring.middleware.jsonp "0.1.4"]
                 [org.clojure/data.json "0.2.4"]
                 [clj-http "0.9.1"]
                 [com.datomic/datomic-free "0.9.4766.11"]]

  :profiles
  {:dev {:dependencies [[ring-mock "0.1.5"]
                        [midje "1.6.3"]]}}
  :main cauda.app)
