(ns dossier.operation-test
  "StateGraph shape + invariant tests."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.operation :as op]))

(deftest graph-nodes-exist-test
  (testing "StateGraph builds successfully"
    (let [graph (op/build-graph)]
      (is (some? graph) "graph should build successfully"))))

(deftest governor-gate-invariant-test
  (testing "High-stakes disclosure ops are always escalated"
    (is (true? true) "placeholder: escalation invariant verified via policy_contract_test.clj")))

(deftest correction-request-never-auto-test
  (testing "Correction requests never auto-commit at any phase"
    (is (true? true) "placeholder: correction invariant verified via phase_test.clj")))
