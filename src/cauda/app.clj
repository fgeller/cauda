(ns cauda.app
  (:use cauda.core
        cauda.store)
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.jsonp :refer [wrap-json-with-padding]]
            [ring.middleware.reload :refer [wrap-reload]]
            [liberator.dev :refer [wrap-trace]])
  (:gen-class :main true))

(def handlers
  (-> app-routes
      (wrap-trace :header :ui)
      (wrap-json-with-padding)
      (wrap-params)
      (wrap-reload '(cauda.core cauda.app))))

(defn boot [port]
  (run-jetty #'handlers {:port port :join? false}))

(defn -main [& args]
  (let [port (or (and (not (empty? args)) (read-string (first args)))
                 (when-let [env-port (System/getenv "CAUDA_PORT")]
                   (read-string env-port))
                 3000)]
    (log "Starting cauda on port" port "connecting to" datomic-uri)
    (boot port)))
