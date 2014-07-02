(ns cauda.store
  (:require [datomic.api :only [q db] :as peer]))

(def datomic-uri "datomic:mem://cauda")
(peer/create-database datomic-uri)

(def conn (peer/connect datomic-uri))
(def db (peer/db conn))

(def schema-tx (read-string (slurp "schema.edn")))
@(peer/transact conn schema-tx)
(def db (peer/db conn))

(defn construct-user [entity]
  {(:user/id entity) {:nick (:user/nick entity)}})

(defn get-all-users-from-db [database]
  (map construct-user
       (map (fn [[entity-id]] (peer/entity database entity-id))
            (peer/q `[:find ?u :where [?u :user/id]] database))))

(get-all-users-from-db db)

(def some-users-tx
  [
   {:db/id (peer/tempid :db.part/user) :user/id 23 :user/nick "hans"}
   {:db/id (peer/tempid :db.part/user) :user/id 24 :user/nick "peter"}
   {:db/id (peer/tempid :db.part/user) :user/id 25 :user/nick "dieter"}
   ])
@(peer/transact conn some-users-tx)

(def db (peer/db conn))
(get-all-users-from-db db)

(defn get-user-from-db [database id]
  (let [entity (peer/entity database (ffirst (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database id)))]
    (construct-user entity)))
(get-user-from-db db 23)

(defn update-user-nick [database user-id new-nick]
  (let [[entity-id] (first (peer/q `[:find ?u :where [?u :user/id ~user-id]] database))
        update-tx [{:db/id entity-id :user/nick new-nick}]]
    @(peer/transact conn update-tx)))

(update-user-nick db 23 "luigi")
(def db (peer/db conn))
(get-all-users-from-db db)

(defn queue-value-for-user [connection database user-id value]
  (let [[user-entity-id] (first (peer/q '[:find ?u :in $ ?i :where [?u :user/id ?i]] database user-id))
        update-tx [{:db/id (peer/tempid :db.part/user) :value/content value :value/queuer user-entity-id :value/queue-time (new java.util.Date)}]]
    @(peer/transact connection update-tx)))

(queue-value-for-user conn db 23 "acme")

(defn queued-values-for-user [database user-id]
  (map (fn [[_ value pt]] {user-id value :pop-time pt})
       (peer/q '[:find ?q ?c ?pt :in $ ?i :where [?u :user/id ?i] [?q :value/queuer ?u] [?q :value/content ?c] [?q :value/pop-time ?pt]] database user-id)))

(def db (peer/db conn))
(queued-values-for-user db 23)

(defn pop-value-for-user [connection database user-id value]
  (let [users-queue (peer/q '[:find ?q :in $ ?i ?c :where [?u :user/id ?i] [?q :value/queuer ?u] [?q :value/content ?c]] database user-id value)
        value-to-pop (ffirst (sort-by (fn [[a]] (:value/queue-time (peer/entity database a))) users-queue))
        update-tx [{:db/id value-to-pop :value/pop-time (new java.util.Date)}]]
    @(peer/transact connection update-tx)))

;; as function in db?

(pop-value-for-user conn db 23 "acme")


(def tx-instants (reverse (sort
                           (peer/q '[:find ?when :where [_ :db/txInstant ?when]] db))))

tx-instants

(peer/shutdown true)
(peer/delete-database datomic-uri)
