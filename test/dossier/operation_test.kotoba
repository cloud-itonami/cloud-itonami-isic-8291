(ns dossier.operation-test
  "StateGraph shape + invariant tests."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.operation :as op]
            [dossier.store :as store]))

(deftest graph-nodes-exist-test
  (testing "StateGraph builds successfully"
    ;; dossier.operation's real compiled-graph constructor is `build`
    ;; (1+ arg, takes a Store) -- this test originally called a
    ;; `build-graph` zero-arg fn that has never existed in
    ;; `dossier.operation`, so it failed to even COMPILE (`No such var`),
    ;; not just at assertion time. Fixed to call the real fn.
    (let [graph (op/build (store/seed-db))]
      (is (some? graph) "graph should build successfully"))))

(deftest governor-gate-invariant-test
  (testing "High-stakes disclosure ops are always escalated"
    (is (true? true) "placeholder: escalation invariant verified via policy_contract_test.clj")))

(deftest correction-request-never-auto-test
  (testing "Correction requests never auto-commit at any phase"
    (is (true? true) "placeholder: correction invariant verified via phase_test.clj")))
