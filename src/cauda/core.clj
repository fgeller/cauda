(ns cauda.core
  (:use cauda.store)
  (:require [liberator.core :refer [resource defresource log!]]
            [compojure.core :refer [defroutes ANY]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [clj-time.core :as joda]
            [clj-time.format :as joda-format]
            [clj-time.coerce :as joda-coerce]
            [clj-time.local :as joda-local]
            [clj-http.client :as client]
            [datomic.api :only [q db] :as peer]))

(def log-date-formatter (joda-format/formatters :date-time))
(defn log-date []
  (joda-format/unparse log-date-formatter (joda-local/local-now)))

(defn log [& args]
  (let [concat-args (defn iter [as acc]
                      (if (empty? as)
                        (string/trim acc)
                        (recur (rest as) (str acc " " (first as)))))
        log-message (format "%s [%s] - %s" (log-date) datomic-uri (concat-args args ""))]
    (println log-message)))

(defn now [] (System/currentTimeMillis))

(defn valid-veto-time? [instant]
  (< (- (now) (* 24 60 60 1000))  (.getTime instant)))

(defn construct-user [entity]
  {(:user/id entity) (merge {:nick (:user/nick entity)}
                            (when-let [waiting-since (:user/waiting-since entity)] {:waitingSince waiting-since}))})

(defn all-users [database]
  (into {}
        (map construct-user
             (map (fn [[entity-id]] (peer/entity database entity-id))
                  (peer/q '[:find ?u :where [?u :user/id]] database)))))

(defn add-user [database data]
  (dosync
   (let [id (+ 1 (count (peer/q '[:find ?u :where [?u :user/id]] database)))]
     (let [user-data (merge
                      {:db/id (peer/tempid :db.part/user) :user/id id}
                      (when-let [nick (get data "nick")] {:user/nick nick}))]
       (log "Adding user for id " id " and data " user-data)
       @(peer/transact (create-database-connection) [user-data]))
     id)))

(defn get-user [database id]
  (let [entity (peer/entity database (ffirst (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database id)))]
    (construct-user entity)))

(defn vetos-for-user [database user-id]
  (let [[user-entity-id]  (first (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database user-id))
        vetos (peer/q '[:find ?c ?t :in $ ?i :where [?v :veto/user ?u] [?v :veto/content ?c] [?v :veto/time ?t]] database user-entity-id)]
    (into {} vetos)))

(defn all-active-vetos [database]
  (flatten
   (map
    (fn [[content instant]] (when (valid-veto-time? instant) content))
    (reverse
     (sort-by second
              (peer/q `[:find ?vc ?vt :where [?v :veto/content ?vc] [?v :veto/time ?vt]] database))))))

(defn queue-value-for-user [database user-id value]
  (let [[user-entity-id] (first (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database user-id))
        update-tx [{:db/id (peer/tempid :db.part/user) :value/content value :value/queuer user-entity-id :value/queue-time (new java.util.Date)}]]
    @(peer/transact (global-connection) update-tx)))

(defn update-waiting-timestamp-for-user [database id timestamp]
  (log "Updating waitingSince to" (new java.util.Date timestamp) "for user" id)
  (let [entity (peer/entity database (ffirst (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database id)))
        entity-id (:db/id entity)
        user-data (if timestamp
                    [:db/add entity-id :user/waiting-since (new java.util.Date timestamp)]
                    [:db/retract entity-id :user/waiting-since (:user/waiting-since entity)])]
    @(peer/transact (create-database-connection) [user-data])))

(defn get-user-queue [database user-id]
  (let [result (peer/q '[:find ?q :in $ ?i :where
                         [?u :user/id ?i]
                         [?q :value/queuer ?u]
                         [?q :value/content ?c]
                         [(missing? $ ?q :value/pop-time)]] database user-id)
        entities (map (fn [[entity-id]] (peer/entity database entity-id)) result)
        sorted-entities (sort-by #(:value/queue-time %) entities)
        contents (map #(:value/content %) sorted-entities)]
    contents))

(defn queue-for-user [database id data]
  (queue-value-for-user database id data)
  (if-not (:waitingSince (get-user database id))
    (update-waiting-timestamp-for-user database id (now)))
  (log "Pushed" data "into user" id "waiting since" (:waitingSince (get-user database id)) "with queue:" (into [] (get-user-queue database id))))

(defn apply-users-veto [database user-id target-value]
  (let [vetoing-user (get-user database user-id)
        vetos (vetos-for-user database user-id)]
    (when (or  (empty? vetos) (nil? (vetos target-value)) (valid-veto-time? (vetos target-value)))
      (let [veto-tx {:db/id (peer/tempid :db.part/user) :veto/content target-value :veto/user user-id :veto/time (new java.util.Date)}]
        @(peer/transact (create-database-connection) [veto-tx])))))

(defn update-waiting-timestamp-for-user [database id timestamp]
  (log "Updating waitingSince to" (joda-coerce/from-long timestamp) "for user" id)
  (let [entity (peer/entity database (ffirst (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database id)))
        entity-id (:db/id entity)
        user-data (if timestamp
                    [:db/add entity-id :user/waiting-since (new java.util.Date timestamp)]
                    [:db/retract entity-id :user/waiting-since (:user/waiting-since entity)])]
    @(peer/transact (create-database-connection) [user-data])))

(defn veto-allowed-for-user? [database user-id]
  (let [vetos (vetos-for-user database user-id)]
    (if vetos
      (> 5 (count (filter (fn [[_ instant]] (valid-veto-time? instant)) vetos)))
      true)))

(defn find-longest-waiting-users [users user-count]
  (take user-count
        (map (fn [[id _]] id)
             (sort-by (fn [[_ user]] (:waitingSince user)) (seq users)))))

(defn flatten-user-queues [count users queues acc]
  (if (zero? count) acc
      (let [next-queues (into {} (map (fn [[id queue]] [id (rest queue)]) queues))
            next-acc (concat acc (map (fn [user] [user (first (get queues user))]) users))]
        (flatten-user-queues (dec count) users next-queues next-acc))))

(defn find-next-values [database value-count]
  (let [users (all-users database)
        longest-waiting-users (find-longest-waiting-users users (count users))
        sorted-users-queues (map (fn [id] [id (get-user-queue database id)]) longest-waiting-users)
        active-vetos (all-active-vetos database)
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
    (log "Found next" value-count "values to be" (into [] (take value-count flattened-queue)))
    (take value-count flattened-queue)))

(defn pop-value-for-user [database user-id value]
  (let [result (peer/q '[:find ?q :in $ ?i :where
                         [?u :user/id ?i]
                         [?q :value/queuer ?u]
                         [(missing? $ ?q :value/pop-time)]] database user-id)
        entities (map (fn [[entity-id]] (peer/entity database entity-id)) result)
        sorted-entities (sort-by #(:value/queue-time %) entities)
        entities-with-content (map (fn [entity] [entity (:value/content entity)]) sorted-entities)
        active-vetos (all-active-vetos database)
        vetos-to-pop (map
                      (fn [[entity _]] {:db/id (:db/id entity) :value/pop-time (new java.util.Date)})
                      (take-while (fn [[entity content]] (some #(= content %) active-vetos)) entities-with-content))
        update-tx {:db/id (:db/id (nth sorted-entities (count vetos-to-pop))) :value/pop-time (new java.util.Date)}]
    @(peer/transact (global-connection) (cons update-tx vetos-to-pop))))

(defn find-last-pop [database]
  (let [last-pop-time-attribute (last (peer/datoms database :avet :value/pop-time))
        last-pop (:value/content (peer/entity database (:e last-pop-time-attribute)))]
    last-pop))

(defn find-next-value [database]
  (let [[id value] (first (find-next-values database 1))]
    (if id
      (let [new-timestamp (when-not (= 1 (count (get-user-queue database id))) (now))]
        (dosync
         (pop-value-for-user database id value)
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
      (if-let [body (:body-string context)]
        (let [data (json/read-str body)]
          [false {key data}])
        {:message "No body"})
      (catch Exception e
        (.printStackTrace e)
        {:message (format "IOException: " (.getMessage e))}))))

(defmacro with-context [& rest]
  `(fn [~'context] ~@rest))

(def json-resource
  {:service-available? (with-context
                         (let [body-string (body-as-string context)
                               request-uuid (java.util.UUID/randomUUID)
                               request (:request context)
                               request-entity-id (peer/tempid :db.part/user)
                               headers-txs (map (fn [[name value]] {:db/id (peer/tempid :db.part/user) :param/request request-entity-id :param/type "header" :param/name (str name) :param/value value})
                                                (:headers request))
                               form-params-txs (map (fn [[name value]] {:db/id (peer/tempid :db.part/user) :param/request request-entity-id :param/type "form-param" :param/name (str name) :param/value value})
                                                    (:form-params request))
                               query-params-txs (map (fn [[name value]] {:db/id (peer/tempid :db.part/user) :param/request request-entity-id :param/type "query-param" :param/name (str name) :param/value value})
                                                     (:query-params request))
                               params-txs (map (fn [[name value]] {:db/id (peer/tempid :db.part/user) :param/request request-entity-id :param/type "param" :param/name (str name) :param/value value})
                                               (:params request))
                               request-tx (merge  {
                                                   :db/id request-entity-id
                                                   :request/uuid request-uuid
                                                   :request/time (new java.util.Date)
                                                   :request/method (str (:request-method request))
                                                   :request/scheme (str (:scheme request))
                                                   :request/server-name (:server-name request)
                                                   :request/server-port (:server-port request)
                                                   :request/uri (:uri request)
                                                   }
                                                  (when-let [content-type (:content-type request)] {:request/content-type content-type})
                                                  (when body-string {:request/body body-string}))

                               request-transactions (vec (concat [request-tx] headers-txs form-params-txs query-params-txs params-txs))
                               ]
                           (log "Received request" request)
                           ;; do we need the @ here?
                           @(peer/transact (global-connection) request-transactions)
                           {:body-string body-string}))
   :available-media-types ["application/json"]
   :known-content-type? #(check-content-type % ["application/json"])
   :malformed? #(parse-json % ::data)})

(defresource user-by-id-resource [id]
  json-resource
  :allowed-methods [:get]
  :exists? (with-context
             (let [user (database-> (get-user id))]
               (when user {::user user})))
  :handle-ok ::user)

(defresource users-resource
  json-resource
  :allowed-methods [:get :post]
  :post! (with-context
           (let [data (::data context)
                 new-id (database-> (add-user data))]
             (when new-id {::id new-id})))
  :handle-ok (with-context
               (database-> (all-users))))

(defresource vetos-resource
  json-resource
  :allowed-methods [:get]
  :handle-ok (with-context (database-> (all-active-vetos))))

(defresource users-queue-resource [id]
  json-resource
  :allowed-methods [:get :post]
  :exists? (with-context
             (let [database (read-database (global-connection))
                   user (get-user database id)]
               [user {::user user ::database database}]))
  :post! (with-context
           (queue-for-user (::database context) id ((::data context) "data")))
  :handle-ok (with-context
               (get-user-queue (::database context) id)))

(defresource users-veto-resource [id]
  json-resource
  :allowed-methods [:post]
  :malformed? (with-context
                (let [database (read-database (global-connection))
                      parse-result (or (not (veto-allowed-for-user? database id))
                                       (parse-json context ::data))
                      augmented-with-database (if (and (vector? parse-result) (not (first parse-result)))
                                                [false (merge (second parse-result) {::database database})]
                                                parse-result)]
                  augmented-with-database))
  :exists? (with-context
             (when-let [user (get-user (::database context) id)] {::user user}))
  :post! (with-context
           (apply-users-veto (::database context) id ((::data context) "data"))))

(defresource queue-pop-resource
  json-resource
  :allowed-methods [:get]
  :handle-ok (with-context
               {"data" (database-> (find-next-value))}))

(defresource queue-last-pop-resource
  json-resource
  :allowed-methods [:get]
  :handle-ok (with-context
               {"data" (database-> (find-last-pop))}))

(defresource queue-resource
  json-resource
  :allowed-methods [:get]
  :handle-ok (with-context
               {"data" (map (fn [[user-id value]]  {user-id value})
                            (database-> (find-next-values 5)))}))

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
