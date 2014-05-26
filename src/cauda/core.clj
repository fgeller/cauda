(ns cauda.core
  (:require [liberator.core :refer [resource defresource]]
            [liberator.dev :refer [wrap-trace]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes ANY]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clj-http.client :as client]))

(defonce users (ref {}))
(defonce queues (ref {}))

(defn build-user-url [request id]
  (java.net.URL. (format "%s://%s:%s%s/%s"
                (name (:scheme request))
                (:server-name request)
                (:server-port request)
                (:uri request)
                (str id))))

(defn check-content-type [ctx content-types]
  (if (#{:put :post} (get-in ctx [:request :request-method]))
    (or
     (some #{(get-in ctx [:request :headers "content-type"])}
           content-types)
     [false {:message "Unsupported Content-Type"}])
    true))

(defn body-as-string [ctx]
  (if-let [body (get-in ctx [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn parse-json [context key]
  (when (#{:put :post} (get-in context [:request :request-method]))
    (try
      (if-let [body (body-as-string context)]
        (let [data (json/read-str body)]
          [false {key data}])
        {:message "No body"})
      (catch Exception e
        (.printStackTrace e)
        {:message (format "IOException: " (.getMessage e))}))))

(defresource user-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :delete]
  :known-content-type? #(check-content-type % ["application/json"])
  :exists? (fn [_] (let [user (get @users id)]
                     (if-not (nil? user) {::user user})))
  :existed? (fn [_] (nil? (get @users id ::sentinel)))
  :available-media-types ["application/json"]
  :malformed? #(parse-json % ::data)
  :can-put-to-missing? false
  :delete! (fn [_] (dosync (alter users assoc id nil)))
  :handle-ok ::user)

(defresource user-list-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get :post]
  :known-content-type? #(check-content-type % ["application/json"])
  :malformed? #(parse-json % ::data)
  :post! #(let [id (str (inc (rand-int 1000000)))]
            (dosync (alter users assoc id (::data %)))
            {::id id})
  :handle-ok @users)

(defroutes app
  ;; (ANY "/queues/:user-id" [user-id] queue-list-resource)
  ;; (ANY "/queues" [] queue-resource)
  (ANY "/users/:id" [id] (user-resource id))
  (ANY "/users" [] user-list-resource)
  (ANY "/" [] (resource :available-media-types ["text/html"]
                        :handle-ok "<html>Hello, Internet -- cauda here.</html>")))

(def handler
  (-> app
      (wrap-trace :header :ui)
      (wrap-params)))

;; (client/post "http://localhost:3000/users" {:body "{ \"name\": \"hans\" }" :content-type :json})
;; (client/post "http://localhost:3000/users" {:body "{ \"name\": \"gerd\" }" :content-type :json})
;; (client/get "http://localhost:3000/users")
;; (client/get "http://localhost:3000/users/790720")

(run-jetty #'handler {:port 3000})
