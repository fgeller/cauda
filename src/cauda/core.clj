(ns cauda.core
  (:require [liberator.core :refer [resource defresource log!]]
            [liberator.dev :refer [wrap-trace]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.jsonp :refer [wrap-json-with-padding]]
            [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes ANY]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clj-http.client :as client]))

(defonce users (ref {}))
(defonce queues (ref {}))
(def user-counter (atom 0))

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
  :exists? (fn [_] (let [user (get @users (Integer/parseInt id))]
                     (if-not (nil? user) {::user user})))
  :existed? (fn [_] (nil? (get @users (Integer/parseInt id) ::sentinel)))
  :available-media-types ["application/json"]
  :malformed? #(parse-json % ::data)
  :can-put-to-missing? false
  :delete! (fn [_] (dosync (alter users assoc (Integer/parseInt id) nil)))
  :handle-ok ::user)

(defresource user-list-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get :post]
  :known-content-type? #(check-content-type % ["application/json"])
  :malformed? #(parse-json % ::data)
  :post! #(dosync
           (swap! user-counter inc)
           (let [id @user-counter
                 data %]
             (println "Adding user for id " id " and data " (::data data))
             (alter users assoc id (::data data))
             (alter queues assoc id [])
             {::id id}))
  :handle-ok (fn [_]
               (println "Listing users: " @users)
               @users))


(defn push-into-user-queue [user-id data]
  (dosync (alter queues (fn [old-queues new-data]
                          (assoc old-queues user-id (conj (get old-queues user-id) new-data))) (get data "data"))
          (if-not (get (@users user-id) "waitingSince")
            (do
             (println "Adding waitingSince timestamp for user " user-id)
             (alter users
                    (fn [old-users timestamp]
                      (assoc old-users
                        user-id
                        (assoc (get old-users user-id) "waitingSince" timestamp)))
                    (System/currentTimeMillis))
             (println "Updated users: " @users))))
  (println "Pushed " (data "data") " into user " user-id "'s queue " @queues)
  user-id)

(defresource user-queue-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :delete :post]
  :known-content-type? #(check-content-type % ["application/json"])
  :exists? (fn [_] (let [user (get @users (Integer/parseInt id))]
                     (if-not (nil? user) {::user user})))
  :existed? (fn [_] (nil? (get @users (Integer/parseInt id) ::sentinel)))
  :available-media-types ["application/json"]
  :malformed? #(parse-json % ::data)
  :post! #(push-into-user-queue (Integer/parseInt id) (::data %))
  :handle-ok (fn [_]
               (println "Listing queue for id " id " " @queues)
               (get @queues (Integer/parseInt id))))


(defresource queue-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :known-content-type? #(check-content-type % ["application/json"])
  :available-media-types ["application/json"]
  :handle-ok (fn [_]
               (let [users-with-values (select-keys @queues (for [[k v] @queues :when (> (count v) 0)] k))
                     [user-id _] (reduce (fn [[last-id _] [new-id _]]
                                              (if (or
                                                   (nil? last-id)
                                                   (> ((@users last-id) "waitingSince")
                                                         ((@users new-id) "waitingSince")))
                                                [new-id (@users new-id)]
                                                [last-id (@users last-id)]))
                                            [nil nil]
                                            (seq users-with-values))]
                 (println "Picking user-id " user-id)
                 (if-not (nil? user-id)
                   (let [random-value (first (get @queues user-id))]
                     (dosync (alter queues (fn [old] (assoc old user-id (vec (rest (get old user-id))))))
                             (do
                               (alter users
                                      (fn [old-users timestamp]
                                        (println "Updating waitingSince timestamp for user " user-id " to " timestamp)
                                        (assoc old-users
                                          user-id
                                          (assoc (get old-users user-id) "waitingSince" timestamp)))
                                      (if (empty? (@queues user-id))
                                        nil
                                        (System/currentTimeMillis)))
                               (println "Updated users: " @users)))
                     (println "Popping " random-value " from user " user-id " queue:"  (@queues user-id))
                     {"data" random-value})))))


(defroutes app-routes
  (ANY "/queue/pop" [] queue-resource)
  (ANY "/users/:id" [id] (user-resource id))
  (ANY "/users/:id/queue" [id] (user-queue-resource id))
  (ANY "/users" [] user-list-resource)
  (ANY "/" [] (resource :available-media-types ["text/html"]
                        :handle-ok "<html>Hello, Internet -- cauda here.</html>")))


(def handler
  (-> app-routes
      (wrap-json-with-padding)
      (wrap-trace :header :ui)
      (wrap-params)))

(run-jetty #'handler {:port 3000})


;; POST http://localhost:3000/users
;;    { "nick": "hans" }

;; GET http://localhost:3000/users
;;    {"955239":{"nick":"hans"}}

;; GET http://localhost:3000/users/955239/queue
;; [ { "data": "234234" }]

;; POST http://localhost:3000/users/955239/queue
;;    { "data": "value" }

;; GET http://localhost:3000/queue/pop
;; "234234"

;; GET http://localhost:3000/queue
;; [ "234234", "22222222",  "1231231223" ]
