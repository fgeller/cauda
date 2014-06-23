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
(d/db conn)
(d/entity (d/db conn) (ffirst results))
(keys entity)
(:user-counter entity)
(let [db (d/db conn)]
  (pprint
   (map #(:user-counter (d/entity db (first %))) results)))

;; add a user
(def some-user {:db/id (d/tempid :db.part/user) :user/id 23 :user/nick "hans"})
@(d/transact conn [some-user])

;; read a user
(def found-user
  (d/entity (d/db conn) (ffirst (d/q '[:find ?u :where [?u :user/id 23]] (d/db conn)))))

(:user/nick found-user)
