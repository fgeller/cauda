(ns cauda.app
  (:use cauda.core)
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.jsonp :refer [wrap-json-with-padding]]
            [ring.middleware.reload :refer [wrap-reload]]))

(def handler
  (-> app-routes
      (wrap-json-with-padding)
      (wrap-params)
      (wrap-reload '(cauda.core cauda.app))))

(defn boot []
  (run-jetty #'handler {:port 3000 :join? false}))
