(ns cauda.core-test
  (:use cauda.app
        cauda.core
        cauda.store
        ring.mock.request
        midje.sweet)
  (:require [clojure.test :refer :all]))

(defn cleanup []
  (dosync
   (delete-database)
   (setup-database)
   (alter queues (fn [_] {}))
   (swap! user-counter (fn [_] 0))
   (swap! last-pop (fn [_] 0))))

(defn add-test-user [nick]
  (let [request (body (content-type (request :post "/users") "application/json") (format "{\"nick\": \"%s\"}" nick))]
    (handlers request)))

(defn add-test-veto [value]
  (let [request (body (content-type (request :post "/users/1/veto") "application/json") (format "{\"data\": \"%s\"}" value))]
    (handlers request)))

(defn queue-test-value [user-id value]
  (let [request (body (content-type (request :post (format "/users/%s/queue" user-id)) "application/json") (format "{\"data\":\"%s\"}" value))]
    (handlers request)))

(defn expect-pop-result [result]
  (let [response (handlers (request :get "/queue/pop"))]
    (:status response) => 200
    (:body response) => (format "{\"data\":\"%s\"}" result)))

(fact "listing users"
      (let [response (handlers (request :get "/users"))]
        (:status response) => 200
        (:body response) => "{}"))

(fact "adding and finding a user"
      (let [request (body (content-type (request :post "/users") "application/json") "{\"nick\": \"felix\"}")
            response (handlers request)]
        (:status response) => 201)

      (let [response (handlers (request :get "/users"))]
        (:status response) => 200
        (:body response) => "{\"1\":{\"nick\":\"felix\"}}")

      (against-background (after :facts (cleanup))))

(fact "adding a user"
      (let [request (body (content-type (request :post "/users") "application/json") "{\"nick\": \"felix\"}")
            response (handlers request)]
        (:status response) => 201)

      (let [response (handlers (request :get "/users"))]
        (:status response) => 200
        (:body response) => "{\"1\":{\"nick\":\"felix\"}}")

      (against-background (after :facts (cleanup))))

(fact "listing on empty cauda"
      (let [response (handlers (request :get "/queue"))]
        (:status response) => 200
        (:body response) => "{\"data\":[]}")

      (let [response (handlers (request :get "/queue/pop"))]
        (:status response) => 200
        (:body response) => "{\"data\":null}"))

(fact "we select from users' queues in turns"

      (add-test-user "fix")
      (add-test-user "fox")

      (queue-test-value 1 "tnt")
      (queue-test-value 1 "tnt2")

      (Thread/sleep 100)

      (queue-test-value 2 "acme")
      (queue-test-value 2 "acme2")

      (let [response (handlers (request :get "/queue"))]
        (:status response) => 200
        (:body response) => "{\"data\":[{\"1\":\"tnt\"},{\"2\":\"acme\"},{\"1\":\"tnt2\"},{\"2\":\"acme2\"}]}")

      (expect-pop-result "tnt")
      (expect-pop-result "acme")
      (expect-pop-result "tnt2")
      (expect-pop-result "acme2")

      (against-background (after :facts (cleanup))))

(fact "queueing and finding values"
      (add-test-user "gerd")

      (let [request (body (content-type (request :post "/users/1/queue") "application/json") "{\"data\":\"tnt\"}")
            response (handlers request)]
        (:status response) => 201)

      (let [response (handlers (request :get "/queue"))]
        (:status response) => 200
        (:body response) => "{\"data\":[{\"1\":\"tnt\"}]}")
      (against-background (after :facts (cleanup))))

(fact "listing vetos"
    (let [response (handlers (request :get "/vetos"))]
      (:status response) => 200
      (:body response) => "[]"))

(fact "adding a veto means a song is ignored while veto is valid"
      (add-test-user "gerd")
      (let [request (body (content-type (request :post "/users/1/veto") "application/json") "{\"data\": \"acme\"}")
            response (handlers request)]
        (:status response) => 201)

      (let [response (handlers (request :get "/vetos"))]
        (:status response) => 200
        (:body response) => "[\"acme\"]")

      (queue-test-value 1 "tnt")
      (queue-test-value 1 "acme")

      (let [response (handlers (request :get "/queue/pop"))]
        (:status response) => 200
        (:body response) => "{\"data\":\"tnt\"}")

      (against-background (after :facts (cleanup))))

(fact "we drop only veto'd values in front of a selected value"
      (add-test-user "gerd")
      (let [request (body (content-type (request :post "/users/1/veto") "application/json") "{\"data\": \"acme\"}")
            response (handlers request)]
        (:status response) => 201)

      (queue-test-value 1 "acme")
      (queue-test-value 1 "tnt")
      (queue-test-value 1 "acme")

      (let [response (handlers (request :get "/users/1/queue"))]
        (:status response) => 200
        (:body response) => "[\"acme\",\"tnt\",\"acme\"]")

      (let [response (handlers (request :get "/queue/pop"))]
        (:status response) => 200
        (:body response) => "{\"data\":\"tnt\"}")

      (let [response (handlers (request :get "/users/1/queue"))]
        (:status response) => 200
        (:body response) => "[\"acme\"]")

      (against-background (after :facts (cleanup))))

(fact "vetos are capped at 5 per user per day"
      (add-test-user "gerd")
      (add-test-veto "a")
      (add-test-veto "b")
      (add-test-veto "c")
      (add-test-veto "d")
      (add-test-veto "e")

      (let [request (body (content-type (request :post "/users/1/veto") "application/json") "{\"data\": \"f\"}")
            response (handlers request)]
        (:status response) => 400)

      (let [response (handlers (request :get "/vetos"))]
        (:status response) => 200
        (:body response) => "[\"e\",\"d\",\"c\",\"b\",\"a\"]")

      (against-background (after :facts (cleanup))))
