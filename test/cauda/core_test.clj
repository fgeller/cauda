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

(defn add-test-veto [value]
  (let [request (body (content-type (request :post "/users/1/veto") "application/json") (format "{\"data\": \"%s\"}" value))]
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
        (:body response) => "{\"1\":{\"nick\":\"felix\"}}")

      (against-background (after :facts (cleanup))))

(fact "adding and deleting a user"
      (let [request (body (content-type (request :post "/users") "application/json") "{\"nick\": \"felix\"}")
            response (handler request)]
        (:status response) => 201)

      (let [response (handler (request :get "/users"))]
        (:status response) => 200
        (:body response) => "{\"1\":{\"nick\":\"felix\"}}")

      (let [request (request :delete "/users/1")
            response (handler request)]
        (:status response) => 204)

      (let [response (handler (request :get "/users"))]
        (:status response) => 200
        (:body response) => "{}")

      (against-background (after :facts (cleanup))))

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
        (:body response) => "{\"data\":[{\"1\":\"tnt\"}]}")
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

(fact "we drop only veto'd values in front of a selected value"
      (add-test-user)
      (let [request (body (content-type (request :post "/users/1/veto") "application/json") "{\"data\": \"acme\"}")
            response (handler request)]
        (:status response) => 201)

      (queue-test-value "acme")
      (queue-test-value "tnt")
      (queue-test-value "acme")

      (let [response (handler (request :get "/users/1/queue"))]
        (:status response) => 200
        (:body response) => "[\"acme\",\"tnt\",\"acme\"]")

      (let [response (handler (request :get "/queue/pop"))]
        (:status response) => 200
        (:body response) => "{\"data\":\"tnt\"}")

      (let [response (handler (request :get "/users/1/queue"))]
        (:status response) => 200
        (:body response) => "[\"acme\"]")

      (against-background (after :facts (cleanup))))

(fact "vetos are capped at 5 per user per day"
      (add-test-user)
      (add-test-veto "a")
      (add-test-veto "b")
      (add-test-veto "c")
      (add-test-veto "d")
      (add-test-veto "e")

      (let [request (body (content-type (request :post "/users/1/veto") "application/json") "{\"data\": \"f\"}")
            response (handler request)]
        (:status response) => 400)

      (let [response (handler (request :get "/vetos"))]
        (:status response) => 200
        (:body response) => "[\"e\",\"d\",\"c\",\"b\",\"a\"]")

      (against-background (after :facts (cleanup))))
