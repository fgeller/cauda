(ns cauda.core
  (:require [liberator.core :refer [resource defresource log!]]
            [compojure.core :refer [defroutes ANY]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clj-http.client :as client]))

(defonce users (ref {}))
(defonce queues (ref {}))
(defonce vetos (ref {}))
(def user-counter (atom 0))
(def last-pop (atom nil))

(defn now [] (System/currentTimeMillis))

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

(defn all-active-vetos []
  (let [vetos (filter identity (map (fn [[_ u]] (u "vetos")) (all-users)))
        active-vetos (flatten (map (fn [veto]
                                     (let [veto-target (first (keys veto))
                                           veto-info (veto veto-target)]
                                       (if (> (veto-info "validUntil") (now)) veto-target)))
                                   vetos))]
    active-vetos))

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
    (update-waiting-timestamp-for-user id (now)))
  (println "Pushed [" data "] into user [" id "] queue: " (get-user-queue id)))

(defn apply-users-veto [id target-value]
  (let [vetoing-user (get-user id)
        vetos (vetoing-user "vetos")]
    (if (or (nil? vetos) (nil? (vetos target-value)) (< ((vetos target-value) "validUntil") (now)))
      (let [new-vetos (update-in vetos [target-value] (fn [_] {"validUntil" (+ (now) (* 1000 60 60 24))}))]
        (set-property-on-user id "vetos" new-vetos)))))

(defn drop-first-from-users-queue [id]
  (println "Drop element [" (first ((all-queues) id)) "] from user [" id "] queue: ")
  (alter queues (fn [qs] (assoc qs id (vec (rest (qs id)))))))

(defn find-longest-waiting-users [users user-count]
  (take user-count
        (map (fn [[id _]] id)
             (sort-by (fn [[_ user]] (user "waitingSince")) (seq users)))))

(defn flatten-user-queues [count queues acc]
  (if (= count 0) acc
      (flatten-user-queues (dec count)
                  (map (fn [[id queue]] [id (rest queue)]) queues)
                  (concat acc (map (fn [[id queue]] [id (first queue)]) queues)))))

(defn find-next-values [value-count]
  (let [longest-waiting-users (find-longest-waiting-users (all-users) (count (all-users)))
        sorted-users-queues (map (fn [id] [id (get-user-queue id)]) longest-waiting-users)
        active-vetos (all-active-vetos)
        filtered-users-queues (map (fn [queue] (filter (fn [value] (not (some #(= % value) active-vetos)))
                                                       queue))
                                   sorted-users-queues)
        max-queue-length (reduce max 0 (map count filtered-users-queues))
        padded-flattened-queues (flatten-user-queues max-queue-length filtered-users-queues nil)
        flattened-queue (filter (fn [[_ queue]] queue) padded-flattened-queues)]
    (println "Found next " value-count " values to be " (take value-count flattened-queue))
    (take value-count flattened-queue)))

(defn find-next-value []
  (let [[id value] (find-next-values 1)]
    (if id
      (let [new-timestamp (if (= 1 (count (get-user-queue id))) nil (now))]
        (dosync
         (swap! last-pop (fn [_] value))
         (drop-first-from-users-queue id)
         (update-waiting-timestamp-for-user id new-timestamp))
        value))))

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
  :handle-ok (fn [_] (all-users)))

(defresource vetos-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :known-content-type? #(check-content-type % ["application/json"])
  :malformed? #(parse-json % ::data)
  :handle-ok (fn [_] (all-active-vetos)))

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

(defresource users-veto-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :known-content-type? #(check-content-type % ["application/json"])
  :exists? (fn [_] (let [user (get-user id)]
                     (if-not (nil? user) {::user user})))
  :existed? (fn [_] (nil? (get-user id)))
  :available-media-types ["application/json"]
  :malformed? #(parse-json % ::data)
  :post! #(apply-users-veto id ((::data %) "data"))
  :handle-ok (fn [_] nil))

(defresource queue-pop-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :known-content-type? #(check-content-type % ["application/json"])
  :available-media-types ["application/json"]
  :handle-ok (fn [_] {"data" (find-next-value)}))

(defresource queue-last-pop-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :known-content-type? #(check-content-type % ["application/json"])
  :available-media-types ["application/json"]
  :handle-ok (fn [_] {"data" @last-pop}))

(defresource queue-resource
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :known-content-type? #(check-content-type % ["application/json"])
  :available-media-types ["application/json"]
  :handle-ok (fn [_] {"data" (find-next-values 5)}))

(defroutes app-routes
  (ANY "/queue" [] queue-resource)
  (ANY "/queue/pop" [] queue-pop-resource)
  (ANY "/queue/last-pop" [] queue-last-pop-resource)
  (ANY "/users/:id" [id] (user-by-id-resource (Long/parseLong id)))
  (ANY "/users/:id/queue" [id] (users-queue-resource (Long/parseLong id)))
  (ANY "/users/:id/veto" [id] (users-veto-resource (Long/parseLong id)))
  (ANY "/users" [] users-resource)
  (ANY "/vetos" [] vetos-resource)
  (ANY "/" [] (resource :available-media-types ["text/html"]
                        :handle-ok "<html>Hello, Internet -- cauda here.</html>")))
