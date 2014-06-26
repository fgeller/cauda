(ns cauda.store
  (:require [datomic.api :only [q db] :as d]))
(use 'clojure.pprint)

(def datomic-uri "datomic:mem://cauda")
(d/create-database datomic-uri)

(d/shutdown true)
(d/delete-database datomic-uri)

(def conn (d/connect datomic-uri))
(def db (d/db conn))


(def schema-tx (read-string (slurp "schema.edn")))
@(d/transact conn schema-tx)
(def db (d/db conn))

(defn get-all-users-from-db [database]
  (map (fn [entity] {(:user/id entity) {:nick (:user/nick entity)}})
       (map (fn [[entity-id]] (d/entity database entity-id))
            (d/q `[:find ?u :where [?u :user/id]] database))))


(def some-users-tx
  [
   {:db/id (d/tempid :db.part/user) :user/id 23 :user/nick "hans"}
   {:db/id (d/tempid :db.part/user) :user/id 24 :user/nick "peter"}
   {:db/id (d/tempid :db.part/user) :user/id 25 :user/nick "dieter"}
   ])
@(d/transact conn some-users-tx)

(def db (d/db conn))
(get-all-users-from-db db)

(defn update-user-nick [user-id database]
  (let [[entity-id] (first (d/q `[:find ?u :where [?u :user/id ~user-id]] database))
        update-tx [{:db/id entity-id :user/nick "mario"}]]
    @(d/transact conn update-tx)))

(update-user-nick 23 db)
(def db (d/db conn))
(get-all-users-from-db db)


(def data-tx (read-string (slurp "data-clean-slate.edn")))
@(d/transact conn data-tx)
(def results (d/q '[:find ?c :where [?c :user-counter]] (d/db conn)))
(count results)
(def entity (-> conn d/db (d/entity (ffirst results))))
(d/entity (d/db conn) (ffirst results))
(keys entity)
(:user-counter entity)
(let [db (d/db conn)]
  (pprint
   (map #(:user-counter (d/entity db (first %))) results)))

;; add a user
(def some-users
  [
   {:db/id (d/tempid :db.part/user) :user/id 23 :user/nick "hans"}
   {:db/id (d/tempid :db.part/user) :user/id 24 :user/nick "peter"}
   {:db/id (d/tempid :db.part/user) :user/id 25 :user/nick "dieter"}
   ])

@(d/transact conn some-users)
;; (def found-user
;;   (d/entity (d/db conn) (ffirst (d/q '[:find ?u :where [?u :user/id 23]] (d/db conn)))))


;; read a user
;; (defn get-user-from-db [id]
;;   (d/entity (d/db conn) (ffirst (d/q '[:find ?u :where [?u :user/id id]] (d/db conn)))))

(defn database-connection []
  (d/connect datomic-uri))

(defn database [connection] (d/db connection))

(database-connection)

(database (database-connection))


@(d/transact (database-connection) schema-tx)
@(d/transact (database-connection) some-users)

(defn get-user-from-db [database id]
    (d/entity database
              (ffirst
               (d/q [:find '?u :where ['?u :user/id id]] database))))

(let [db (database (database-connection))]
  (:user/nick
   (get-user-from-db db 23)))

(defn get-all-users-from-db [database]
  (map (fn [entity] {(:user/id entity) {:nick (:user/nick entity)}})
       (map (fn [[entity-id]] (d/entity database entity-id))
            (d/q `[:find ?u :where [?u :user/id]] database))))

(let [database (database (database-connection))]
   (get-all-users-from-db database))



(:user/nick (get-user-from-db 25))

;;

(def tx-instants (reverse (sort
                           (d/q '[:find ?when :where [_ :db/txInstant ?when]] (database-connection)))))

tx-instants
