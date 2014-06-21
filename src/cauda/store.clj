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
