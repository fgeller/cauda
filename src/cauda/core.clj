(ns cauda.core
  (:require [liberator.core :refer [resource defresource log!]]
            [liberator.dev :refer [wrap-trace]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes ANY]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clj-http.client :as client]))

(defonce users (ref {}))
(defonce queues (ref {}))

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
            (dosync
             (alter users assoc id (::data %))
             (alter queues assoc id []))
            {::id id})
  :handle-ok @users)

(defn push-into-user-queue [user-id data]
  (dosync (alter queues (fn [old-queues new-data]
                          (assoc old-queues user-id (conj (get old-queues user-id) new-data))) (get data "data")))
  (log! :trace "pushed data" data " into user " user-id "'s queue " @queues)
  user-id)

(defresource user-queue-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :delete :post]
  :known-content-type? #(check-content-type % ["application/json"])
  :exists? (fn [_] (let [user (get @users id)]
                     (if-not (nil? user) {::user user})))
  :existed? (fn [_] (nil? (get @users id ::sentinel)))
  :available-media-types ["application/json"]
  :malformed? #(parse-json % ::data)
  :post! #(push-into-user-queue id (::data %))
  :handle-ok (fn [_]  (get @queues id)))


(defresource queue-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :known-content-type? #(check-content-type % ["application/json"])
  :available-media-types ["application/json"]
  :handle-ok (fn [_]
               (let [users-with-songs (select-keys @queues (for [[k v] @queues :when (> (count v) 0)] k))
                     user (rand-nth (keys users-with-songs))]
                   (if-not (nil? user)
                     (let [random-song (first (get @queues user))]
                       (dosync (alter queues (fn [old] (assoc old user (vec (rest (get old user)))))))
                       (log! :trace "took thing out of queues " @queues)
                       random-song)))))

(defroutes app
  ;; (ANY "/queues/:user-id" [user-id] queue-list-resource)
  ;; (ANY "/queues" [] queue-resource)

  (ANY "/queue/pop" [] queue-resource)
  (ANY "/users/:id" [id] (user-resource id))
  (ANY "/users/:id/queue" [id] (user-queue-resource id))
  (ANY "/users" [] user-list-resource)
  (ANY "/" [] (resource :available-media-types ["text/html"]
                        :handle-ok "<html>Hello, Internet -- cauda here.</html>")))

(def handler
  (-> app
      (wrap-trace :header :ui)
      (wrap-params)))

;; POST http://localhost:3000/users
;;    { "nick": "hans" }

;; GET http://localhost:3000/users
;;    {"955239":{"nick":"hans"}}

;; GET http://localhost:3000/users/955239/queue
;; [ { "data": "234234" }]

;; POST http://localhost:3000/users/955239/queue
;;    { "data": "song-id" }

;; GET http://localhost:3000/queue/pop
;; "234234"

;; GET http://localhost:3000/queue
;; [ "234234", "22222222",  "1231231223" ]


;; (client/post "http://localhost:3000/users" {:body "{ \"name\": \"hans\" }" :content-type :json})
;; (client/post "http://localhost:3000/users" {:body "{ \"name\": \"gerd\" }" :content-type :json})
;; (client/get "http://localhost:3000/users")
;; (client/get "http://localhost:3000/users/790720")
  ;; 120  curl -H "Content-Type: application/json" -i -X POST -d '{"blubb": 23 }' localhost:3000/users
  ;; 121  curl -X GET localhost:3000/users
  ;; 122  curl -X GET localhost:3000/users/185927
  ;; 123  curl -X GET localhost:3000/users
  ;; 124  curl -H "Content-Type: application/json" -i -X POST -d '{"blubb": 23 }' localhost:3000/users
  ;; 125  curl -X GET localhost:3000/users
  ;; 126  curl -X GET localhost:3000/users/994684
  ;; 127  curl -X GET localhost:3000/users/994684
  ;; 128  history

(run-jetty #'handler {:port 3000})
