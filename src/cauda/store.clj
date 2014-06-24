(ns cauda.store
  (:require [datomic.api :only [q db] :as d]))
(use 'clojure.pprint)

;; trying datomic
(def datomic-uri "datomic:mem://cauda")
(d/create-database datomic-uri)
(def conn (d/connect datomic-uri))
(def schema-tx (read-string (slurp "schema.edn")))
@(d/transact conn schema-tx)
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
  (d/db (d/connect datomic-uri)))

(database-connection)

(defn get-user-from-db [connection id]
    (d/entity connection
              (ffirst
               (d/q [:find '?u :where ['?u :user/id id]] connection))))

(let [connection (database-connection)]
  (:user/nick
   (get-user-from-db connection 23)))

(defn get-all-users-from-db [connection]
  (map (fn [entity] {(:user/id entity) {:nick (:user/nick entity)}})
       (map (fn [[entity-id]] (d/entity connection entity-id))
            (d/q `[:find ?u :where [?u :user/id]] connection))))

(let [connection (database-connection)]
   (get-all-users-from-db connection))



(:user/nick (get-user-from-db 25))
