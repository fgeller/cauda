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

(defn get-user [id]
  (get @users id))

(defn delete-user [id]
  (println "Deleting user [" id "]")
  (dosync (alter users dissoc id nil)))

(defn add-user [data]
  (dosync
   (swap! user-counter inc)
   (let [id @user-counter]
     (println "Adding user for id " id " and data " data)
     (alter users assoc  id data)
     (alter queues assoc id [])
     id)))

(defn all-users [] @users)

(defn all-queues [] @queues)

(defn set-property-on-user [id key val]
  (dosync
   (alter users
          (fn [old-users]
            (update-in old-users [id] (fn [old-user] (update-in old-user [key] (fn [_] val))))))))

(defn push-into-user-queue [id data]
  (dosync
   (alter queues (fn [qs] (update-in qs [id] (fn [old] (conj old data)))))))

(defn update-waiting-timestamp-for-user [id timestamp]
  (println "Updating waitingSince timestamp for user " id)
  (set-property-on-user id "waitingSince" timestamp))

(defn get-user-queue [id] ((all-queues) id))

(defn queue-for-user [id data]
  (push-into-user-queue id data)
  (if-not ((get-user id) "waitingSince")
    (update-waiting-timestamp-for-user id (System/currentTimeMillis)))
  (println "Pushed [" data "] into user [" id "] queue: " (get-user-queue id)))

(defn drop-first-from-users-queue [id]
  (println "Drop element [" (first ((all-queues) id)) "] from user [" id "] queue: ")
  (alter queues (fn [qs] (assoc qs id (vec (rest (qs id)))))))

(defn find-longest-waiting-user [users]
  (reduce (fn [last-id new-id]
            (if (or
                 (nil? last-id)
                 (> ((users last-id) "waitingSince") ((users new-id) "waitingSince")))
              new-id
              last-id))
          nil
          (seq (keys users))))

(defn find-next-value []
  (let [users-with-values (select-keys (all-queues) (for [[k v] (all-queues) :when (not (empty? v))] k))
        id (find-longest-waiting-user users-with-values)]
    (println "Picking user-id " id)
    (if-not (nil? id)
      (let [random-value (first (get-user-queue id))
            new-timestamp (if (= 1 (count (get-user-queue id))) nil (System/currentTimeMillis))]
        (dosync (drop-first-from-users-queue id)
                (update-waiting-timestamp-for-user id new-timestamp))
        {"data" random-value}))))

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

(defresource user-by-id-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :delete]
  :known-content-type? #(check-content-type % ["application/json"])
  :exists? (fn [_] (let [user (get-user id)]
                     (if-not (nil? user) {::user user})))
  :existed? (fn [_] (nil? (get-user id)))
  :available-media-types ["application/json"]
  :malformed? #(parse-json % ::data)
  :can-put-to-missing? false
  :delete! (fn [_] (delete-user id))
  :handle-ok ::user)

(defresource users-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get :post]
  :known-content-type? #(check-content-type % ["application/json"])
  :malformed? #(parse-json % ::data)
  :post! (fn [data] {::id (add-user (::data data))})
  :handle-ok (fn [_] (let [users (all-users)] users)))

(defresource users-queue-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :delete :post]
  :known-content-type? #(check-content-type % ["application/json"])
  :exists? (fn [_] (let [user (get-user id)]
                     (if-not (nil? user) {::user user})))
  :existed? (fn [_] (nil? (get-user id)))
  :available-media-types ["application/json"]
  :malformed? #(parse-json % ::data)
  :post! #(queue-for-user id ((::data %) "data"))
  :handle-ok (fn [_] (get-user-queue id)))

(defresource queue-pop-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :known-content-type? #(check-content-type % ["application/json"])
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (find-next-value)))

(defroutes app-routes
  (ANY "/queue/pop" [] queue-pop-resource)
  (ANY "/users/:id" [id] (user-by-id-resource (Integer/parseInt id)))
  (ANY "/users/:id/queue" [id] (users-queue-resource (Integer/parseInt id)))
  (ANY "/users" [] users-resource)
  (ANY "/" [] (resource :available-media-types ["text/html"]
                        :handle-ok "<html>Hello, Internet -- cauda here.</html>")))

(def handler
  (-> app-routes
      (wrap-json-with-padding)
      (wrap-trace :header :ui)
      (wrap-params)))

(run-jetty #'handler {:port 3000})
