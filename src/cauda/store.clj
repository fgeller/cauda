(ns cauda.store
  (:require [datomic.api :only [q db] :as peer]))

(def datomic-uri "datomic:mem://cauda")
(defn shutdown-database [] (peer/shutdown true))
(defn delete-database [] (peer/delete-database datomic-uri))
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
(defmacro database-> [& rest]
  `(-> (read-database (global-connection))
       ~@rest))

(setup-database)

(defn global-connection [] (create-database-connection))
(def db (read-database (global-connection)))

;; (defn playing-around []

;;   (def some-users-tx
;;     [
;;      {:db/id (peer/tempid :db.part/user) :user/id 23 :user/nick "hans"}
;;      {:db/id (peer/tempid :db.part/user) :user/id 24 :user/nick "peter"}
;;      {:db/id (peer/tempid :db.part/user) :user/id 25 :user/nick "dieter"}
;;      ])
;;   @(peer/transact global-connection some-users-tx)

;;   (get-all-users-from-db db)
;;   (update-user-nick db 23 "luigi")
;;   (get-all-users-from-db db)
;;   (queue-value-for-user global-connection db 23 "acme")
;;   (queued-entities-for-user db 23)
;;   (pop-value-for-user global-connection db 23 "acme")
;;   (get-all-users-from-db db)

;;   (def tx-instants (reverse (sort
;;                              (peer/q '[:find ?when :where [_ :db/txInstant ?when]] db))))

;;   tx-instants)
