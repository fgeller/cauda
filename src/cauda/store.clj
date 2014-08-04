(ns cauda.store
  (:require [datomic.api :only [q db] :as peer]))

(def datomic-uri (or (System/getenv "CAUDA_DB_URI")
                     "datomic:mem://cauda-test"))

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
