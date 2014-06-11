(ns cauda.core-test
  (:use cauda.app cauda.core ring.mock.request)
  (:require [clojure.test :refer :all]))

(deftest test-cauda

  (testing "listing users"
    (let [response (handler (request :get "/users"))]
      (is (= (:status response) 200))
      (is (= (:body response) "{}"))))

  (testing "adding a user"
    (let [request (body  (content-type (request :post "/users") "application/json") "{\"nick\": \"felix\"}")
          response (handler request)]
      (is (= (:status response) 201))))

  (testing "listing users"
    (let [response (handler (request :get "/users"))]
      (is (= (:status response) 200))
      (is (= (:body response) "{\"1\":{\"nick\":\"felix\"}}")))))
