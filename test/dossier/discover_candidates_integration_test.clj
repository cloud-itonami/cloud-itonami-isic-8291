(ns dossier.discover-candidates-integration-test
  "Integration test: verify :disclosure/discover-candidates operation end-to-end."
  (:require [clojure.test :refer [deftest is]]
            [langgraph.graph :as g]
            [dossier.store :as store]
            [dossier.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(deftest discover-candidates-isic-0610-end-to-end
  "Call :disclosure/discover-candidates for crude oil (ISIC-0610),
   verify StateGraph execution completes."

  (let [[db actor] (fresh)
        result (g/run* actor {:request {:op :disclosure/discover-candidates
                                        :vertical {:isic "0610"}
                                        :count 3}}
                       {:thread-id "test-isic-0610"})]

    (is (some? result) "Operation should return result")
    (is (contains? result :state) "Result should contain state")))
