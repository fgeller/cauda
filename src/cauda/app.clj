(ns cauda.app
  (:use cauda.core)
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.jsonp :refer [wrap-json-with-padding]]
            [ring.middleware.reload :refer [wrap-reload]])
  (:gen-class :main true))

(def handlers
  (-> app-routes
      (wrap-json-with-padding)
      (wrap-params)
      (wrap-reload '(cauda.core cauda.app))))

(defn boot [port]
  (run-jetty #'handlers {:port port :join? false}))

(defn -main [& args]
  (let [port (if (empty? args) 3000 (read-string (first args)))]
    (log "Starting cauda on port" port)
    (boot port)))
