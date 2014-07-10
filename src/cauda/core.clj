(ns cauda.core
  (:use cauda.store)
  (:require [liberator.core :refer [resource defresource log!]]
            [compojure.core :refer [defroutes ANY]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clj-http.client :as client]
            [datomic.api :only [q db] :as peer]))

(defonce queues (ref {}))
(defonce vetos (ref {}))
(def user-counter (atom 0))
(def last-pop (atom nil))

(defn now [] (System/currentTimeMillis))

(defn construct-user [entity]
  {(:user/id entity) (merge {:nick (:user/nick entity)}
                            (when-let [waiting-since (:user/waiting-since entity)] {:waitingSince waiting-since}))})

(defn all-users-from-db [database]
  (into {}
        (map construct-user
             (map (fn [[entity-id]] (peer/entity database entity-id))
                  (peer/q `[:find ?u :where [?u :user/id]] database)))))



(defn add-user-to-db [database data]
  (dosync
   (swap! user-counter inc)
   (let [id @user-counter]
     (let [user-data (merge
                      {:db/id (peer/tempid :db.part/user) :user/id id}
                      (when-let [nick (get data "nick")] {:user/nick nick}))]
       (log/info "Adding user for id " id " and data " user-data)
       @(peer/transact (create-database-connection) [user-data]))
     (alter queues assoc id [])
     id)))

(defn get-user-from-db [database id]
  (let [entity (peer/entity database (ffirst (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database id)))]
    (construct-user entity)))

(defn all-queues [] @queues)

(defn valid-veto? [veto-info]
  (> (:validUntil veto-info) (now)))

(defn all-active-vetos []
  (let [vetos (filter identity (map (fn [[_ u]] (:vetos u)) (all-users)))
        active-vetos (flatten (map (fn [veto]
                                     (map (fn [[veto-target veto-info]] (if (valid-veto? veto-info) veto-target))
                                          veto))
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

(defn update-waiting-timestamp-for-user [database id timestamp]
  (log/info "Updating waitingSince timestamp for user" id)
  (let [entity (peer/entity database (ffirst (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database id)))
        user-data {:db/id (:db/id entity) :user/waiting-since (new java.util.Date timestamp)}]
    @(peer/transact (create-database-connection) [user-data])))

(defn get-user-queue [id] ((all-queues) id))

(defn queue-for-user [database id data]
  (push-into-user-queue id data)
  (if-not (:waitingSince (get-user-from-db database id))
    (update-waiting-timestamp-for-user database id (now)))
  (log/info "Pushed" data "into user" id "waiting since" (:waitingSince (get-user id)) "with queue:" (get-user-queue id)))

(defn apply-users-veto [id target-value]
  (let [vetoing-user (get-user id)
        vetos (:vetos vetoing-user)]
    (if (or (nil? vetos) (nil? (vetos target-value)) (< (:validUntil (vetos target-value)) (now)))
      (let [new-vetos (update-in vetos [target-value] (fn [_] {:validUntil (+ (now) (* 1000 60 60 24))}))]
        (set-property-on-user id :vetos new-vetos)))))

(defn veto-allowed-for-user? [id]
  (let [vetos (:vetos (get-user id))]
    (if vetos (> 5 (count (filter (fn [[_ info]] (valid-veto? info)) vetos)))
      true)))

(defn drop-from-queue [id value]
  (let [queue ((all-queues) id)
        [dropped-vetos remainder] (split-with (fn [value] (some #{value} (all-active-vetos))) queue)]
    (log/info "Drop leading vetos " dropped-vetos "for value" value "from user" id "queue:" queue)
    (alter queues #(assoc % id (vec (rest remainder))))))

(defn find-longest-waiting-users [users user-count]
  (take user-count
        (map (fn [[id _]] id)
             (sort-by (fn [[_ user]] (:waitingSince user)) (seq users)))))

(defn flatten-user-queues [count users queues acc]
  (if (zero? count) acc
      (let [next-queues (into {} (map (fn [[id queue]] [id (rest queue)]) queues))
            next-acc (concat acc (map (fn [user] [user (first (get queues user))]) users))]
        (flatten-user-queues (dec count) users next-queues next-acc))))

(defn find-next-values [value-count]
  (let [longest-waiting-users (find-longest-waiting-users (all-users) (count (all-users)))
        sorted-users-queues (map (fn [id] [id (get-user-queue id)]) longest-waiting-users)
        active-vetos (all-active-vetos)
        filtered-users-queues (zipmap longest-waiting-users
                                      (map (fn [[id queue]] (filter (fn [value] (not-any? #(= % value) active-vetos))
                                                                    queue))
                                           sorted-users-queues))
        max-queue-length (reduce max 0 (map count filtered-users-queues))
        padded-flattened-queues (flatten-user-queues max-queue-length
                                                     longest-waiting-users
                                                     filtered-users-queues
                                                     nil)
        flattened-queue (filter (fn [[_ value]] value) padded-flattened-queues)]
    (log/info "Found next" value-count "values to be" (take value-count flattened-queue))
    (take value-count flattened-queue)))

(defn find-next-value [database]
  (let [[id value] (first (find-next-values 1))]
    (if id
      (let [new-timestamp (when-not (= 1 (count (get-user-queue id))) (now))]
        (dosync
         (swap! last-pop (fn [_] value))
         (drop-from-queue id value)
         (update-waiting-timestamp-for-user database id new-timestamp))
        value))))

(defn check-content-type [context content-types]
  (if (#{:put :post} (get-in context [:request :request-method]))
    (or
     (some #{(get-in context [:request :headers "content-type"])} content-types)
     [false {:message "Unsupported Content-Type"}])
    true))

(defn body-as-string [context]
  (if-let [body (get-in context [:request :body])]
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

(def json-resource
  {:available-media-types ["application/json"]
   :known-content-type? #(check-content-type % ["application/json"])
   :malformed? #(parse-json % ::data)})

(defmacro request-handler [& rest]
  `(fn [~'context] ~@rest))

;; (defn queue-value-for-user [connection database user-id value]
;;   (let [[user-entity-id] (first (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database user-id))
;;         update-tx [{:db/id (peer/tempid :db.part/user) :value/content value :value/queuer user-entity-id :value/queue-time (new java.util.Date)}]]
;;     @(peer/transact connection update-tx)))

;; (defn queued-entities-for-user [database user-id]
;;   (map first
;;        (peer/q '[:find ?q :in $ ?i :where
;;                  [?u :user/id ?i]
;;                  [?q :value/queuer ?u]
;;                  [(missing? $ ?q :value/pop-time)]] database user-id)))

;; (defn pop-value-for-user [connection database user-id value]
;;   (let [users-queue (queued-entities-for-user database user-id)
;;         value-to-pop (first (sort-by #(:value/queue-time (peer/entity database %)) users-queue))
;;         update-tx [{:db/id value-to-pop :value/pop-time (new java.util.Date)}]]
;;     @(peer/transact connection update-tx)))

(defresource user-by-id-resource [id]
  json-resource
  :allowed-methods [:get]
  :exists? (request-handler
             (let [user (database-> (get-user-from-db id))]
               (when user {::user user})))
  :handle-ok ::user)

(defresource users-resource
  json-resource
  :allowed-methods [:get :post]
  :post! (request-handler
          (let [data (::data context)
                new-id (database-> (add-user-to-db data))]
            (when new-id {::id new-id})))
  :handle-ok (request-handler
              (database-> (all-users-from-db))))

(defresource vetos-resource
  json-resource
  :allowed-methods [:get]
  :handle-ok (fn [_] (all-active-vetos)))

(defresource users-queue-resource [id]
  json-resource
  :allowed-methods [:get :post]
  :exists? (request-handler
            (let [user (database-> (get-user-from-db id))]
              (when user {::user user})))
  :post! (request-handler
          (database-> (queue-for-user id ((::data context) "data"))))
  ;; :handle-ok (request-handler
  ;;             (get-user-queue id))
  )

(defresource users-veto-resource [id]
  json-resource
  :allowed-methods [:post]
  :exists? (fn [_] (let [user (get-user id)]
                     (if user {::user user})))
  :existed? (fn [_] (nil? (get-user id)))
  :malformed? #(or
                (not (veto-allowed-for-user? id))
                (parse-json % ::data))
  :post! #(apply-users-veto id ((::data %) "data"))
  :handle-ok (fn [_] nil))

(defresource queue-pop-resource
  json-resource
  :allowed-methods [:get]
  :handle-ok (request-handler
              {"data" (database-> (find-next-value))}))

(defresource queue-last-pop-resource
  json-resource
  :allowed-methods [:get]
  :handle-ok (fn [_] {"data" @last-pop}))

(defresource queue-resource
  json-resource
  :allowed-methods [:get]
  :handle-ok (fn [_] {"data" (map (fn [[user-id value]]  {user-id value})
                                  (find-next-values 5))}))

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
