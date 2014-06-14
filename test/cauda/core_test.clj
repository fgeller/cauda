(ns cauda.core-test
  (:use cauda.app cauda.core ring.mock.request midje.sweet)
  (:require [clojure.test :refer :all]))

(defn cleanup []
  (dosync
   (alter users (fn [_] {}))
   (alter queues (fn [_] {}))
   (alter vetos (fn [_] {}))
   (swap! user-counter (fn [_] 0))
   (swap! last-pop (fn [_] 0))))

(defn add-test-user []
  (let [request (body (content-type (request :post "/users") "application/json") "{\"nick\": \"felix\"}")]
    (handler request)))

(defn queue-test-value [value]
  (let [request (body (content-type (request :post "/users/1/queue") "application/json") (format "{\"data\":\"%s\"}" value))]
    (handler request)))

(fact "listing users"
      (let [response (handler (request :get "/users"))]
        (:status response) => 200
        (:body response) => "{}"))

(fact "adding and finding a user"
      (let [request (body (content-type (request :post "/users") "application/json") "{\"nick\": \"felix\"}")
            response (handler request)]
        (:status response) => 201)

      (let [response (handler (request :get "/users"))]
        (:status response) => 200
        (:body response) => "{\"1\":{\"nick\":\"felix\"}}"))

(fact "listing on empty cauda"
      (let [response (handler (request :get "/queue"))]
        (:status response) => 200
        (:body response) => "{\"data\":[]}")

      (let [response (handler (request :get "/queue/pop"))]
        (:status response) => 200
        (:body response) => "{\"data\":null}"))

(fact "queueing and finding values"
      (add-test-user)

      (let [request (body (content-type (request :post "/users/1/queue") "application/json") "{\"data\":\"tnt\"}")
            response (handler request)]
        (:status response) => 201)

      (let [response (handler (request :get "/queue"))]
        (:status response) => 200
        (:body response) => "{\"data\":[[1,\"tnt\"]]}")
      (against-background (after :facts (cleanup))))

(fact "listing vetos"
    (let [response (handler (request :get "/vetos"))]
      (:status response) => 200
      (:body response) => "[]"))

(fact "adding a veto means a song is ignored while veto is valid"
      (add-test-user)
      (let [request (body (content-type (request :post "/users/1/veto") "application/json") "{\"data\": \"acme\"}")
            response (handler request)]
        (:status response) => 201)

      (let [response (handler (request :get "/vetos"))]
        (:status response) => 200
        (:body response) => "[\"acme\"]")

      (queue-test-value "tnt")
      (queue-test-value "acme")

      (let [response (handler (request :get "/queue/pop"))]
        (:status response) => 200
        (:body response) => "{\"data\":\"tnt\"}")

      (against-background (after :facts (cleanup))))
