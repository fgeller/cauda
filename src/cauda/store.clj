(ns cauda.store
  (:require [datomic.api :only [q db] :as peer]))

(def datomic-uri "datomic:mem://cauda")
(defn drop-database [] (peer/shutdown))
(defn create-database [] (peer/create-database datomic-uri))
(defn create-database-connection [] (peer/connect datomic-uri))
(defn read-database [connection] (peer/db connection))
(defn install-schema [connection]
  (let [schema-tx (read-string (slurp "schema.edn"))]
    @(peer/transact connection schema-tx)))
(defn setup-database []
  (create-database)
  (-> (create-database-connection)
      (install-schema)))

(setup-database)

(def global-connection (create-database-connection))
(def db (read-database global-connection))

(defn construct-user [entity] {(:user/id entity) {:nick (:user/nick entity)}})

(defn get-all-users-from-db [database]
  (map construct-user
       (map (fn [[entity-id]] (peer/entity database entity-id))
            (peer/q `[:find ?u :where [?u :user/id]] database))))


(defn get-user-from-db [database id]
  (let [entity (peer/entity database (ffirst (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database id)))]
    (construct-user entity)))
(get-user-from-db db 23)

(defn update-user-nick [connection database user-id new-nick]
  (let [[entity-id] (first (peer/q `[:find ?u :where [?u :user/id ~user-id]] database))
        update-tx [{:db/id entity-id :user/nick new-nick}]]
    @(peer/transact connection update-tx)))

(defn queue-value-for-user [connection database user-id value]
  (let [[user-entity-id] (first (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database user-id))
        update-tx [{:db/id (peer/tempid :db.part/user) :value/content value :value/queuer user-entity-id :value/queue-time (new java.util.Date)}]]
    @(peer/transact connection update-tx)))

(defn queued-entities-for-user [database user-id]
  (map first
       (peer/q '[:find ?q :in $ ?i :where
                 [?u :user/id ?i]
                 [?q :value/queuer ?u]
                 [(missing? $ ?q :value/pop-time)]] database user-id)))

(defn pop-value-for-user [connection database user-id value]
  (let [users-queue (queued-entities-for-user database user-id)
        value-to-pop (first (sort-by #(:value/queue-time (peer/entity database %)) users-queue))
        update-tx [{:db/id value-to-pop :value/pop-time (new java.util.Date)}]]
    @(peer/transact connection update-tx)))

(defn playing-around []

  (def some-users-tx
    [
     {:db/id (peer/tempid :db.part/user) :user/id 23 :user/nick "hans"}
     {:db/id (peer/tempid :db.part/user) :user/id 24 :user/nick "peter"}
     {:db/id (peer/tempid :db.part/user) :user/id 25 :user/nick "dieter"}
     ])
  @(peer/transact global-connection some-users-tx)

  (get-all-users-from-db db)
  (update-user-nick db 23 "luigi")
  (get-all-users-from-db db)
  (queue-value-for-user global-connection db 23 "acme")
  (queued-entities-for-user db 23)
  (pop-value-for-user global-connection db 23 "acme")
  (get-all-users-from-db db)

  (def tx-instants (reverse (sort
                             (peer/q '[:find ?when :where [_ :db/txInstant ?when]] db))))

  tx-instants)
