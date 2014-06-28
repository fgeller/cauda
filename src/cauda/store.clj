(ns cauda.store
  (:require [datomic.api :only [q db] :as d]))

(def datomic-uri "datomic:mem://cauda")
(d/create-database datomic-uri)

(def conn (d/connect datomic-uri))
(def db (d/db conn))

(def schema-tx (read-string (slurp "schema.edn")))
@(d/transact conn schema-tx)
(def db (d/db conn))

(defn construct-user [entity]
  {(:user/id entity) {:nick (:user/nick entity)}})

(defn get-all-users-from-db [database]
  (map construct-user
       (map (fn [[entity-id]] (d/entity database entity-id))
            (d/q `[:find ?u :where [?u :user/id]] database))))

(get-all-users-from-db db)

(def some-users-tx
  [
   {:db/id (d/tempid :db.part/user) :user/id 23 :user/nick "hans"}
   {:db/id (d/tempid :db.part/user) :user/id 24 :user/nick "peter"}
   {:db/id (d/tempid :db.part/user) :user/id 25 :user/nick "dieter"}
   ])
@(d/transact conn some-users-tx)

(def db (d/db conn))
(get-all-users-from-db db)

(defn get-user-from-db [database id]
  (let [entity (d/entity database (ffirst (d/q [:find '?u :where ['?u :user/id id]] database)))]
    (construct-user entity)))
(get-user-from-db db 23)

(defn update-user-nick [database user-id new-nick]
  (let [[entity-id] (first (d/q `[:find ?u :where [?u :user/id ~user-id]] database))
        update-tx [{:db/id entity-id :user/nick new-nick}]]
    @(d/transact conn update-tx)))

(update-user-nick db 23 "luigi")
(get-all-users-from-db db)

(def db (d/db conn))
(get-all-users-from-db db)

(def tx-instants (reverse (sort
                           (d/q '[:find ?when :where [_ :db/txInstant ?when]] db))))

tx-instants

(d/shutdown true)
(d/delete-database datomic-uri)
