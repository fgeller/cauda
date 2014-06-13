(ns cauda.core-test
  (:use cauda.app cauda.core ring.mock.request)
  (:require [clojure.test :refer :all]))

(deftest test-users

  (testing "listing users"
    (let [response (handler (request :get "/users"))]
      (is (= (:status response) 200))
      (is (= (:body response) "{}"))))

  (testing "adding a user"
    (let [request (body (content-type (request :post "/users") "application/json") "{\"nick\": \"felix\"}")
          response (handler request)]
      (is (= (:status response) 201))))

  (testing "listing users"
    (let [response (handler (request :get "/users"))]
      (is (= (:status response) 200))
      (is (= (:body response) "{\"1\":{\"nick\":\"felix\"}}"))))

  (testing "listing next songs should be empty"
    (let [response (handler (request :get "/queue"))]
      (is (= (:status response) 200))
      (is (= (:body response) "{\"data\":[]}"))))

  (testing "popping next song should yield empty result"
    (let [response (handler (request :get "/queue/pop"))]
      (is (= (:status response) 200))
      (is (= (:body response) "{\"data\":null}"))))

  (testing "queuing a value"
    (let [request (body (content-type (request :post "/users/1/queue") "application/json") "{\"data\":\"tnt\"}")
          response (handler request)]
      (is (= (:status response) 201))))

  (testing "listing next songs should include the queued song"
    (let [response (handler (request :get "/queue"))]
      (is (= (:status response) 200))
      (is (= (:body response) "{\"data\":[[1,\"tnt\"]]}"))))

  (testing "listing vetos"
    (let [response (handler (request :get "/vetos"))]
      (is (= (:status response) 200))
      (is (= (:body response) "[]"))))

  (testing "adding a veto"
    (let [request (body (content-type (request :post "/users/1/veto") "application/json") "{\"data\": \"acme\"}")
          response (handler request)]
      (is (= (:status response) 201))))

  (testing "listing vetos"
    (let [response (handler (request :get "/vetos"))]
      (is (= (:status response) 200))
      (is (= (:body response) "[\"acme\"]"))))

  (testing "queuing a veto'd value"
    (let [request (body (content-type (request :post "/users/1/queue") "application/json") "{\"data\": \"acme\"}")
          response (handler request)]
      (is (= (:status response) 201))))

  (testing "pop'ing the next value should skip the veto'd value and yield an empty result."
    (let [response (handler (request :get "/queue/pop"))]
      (is (= (:status response) 200))
      (is (= (:body response) "{\"data\":null}")))))
