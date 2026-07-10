(ns dossier.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than the governor: hold writes that
  aren't enabled yet, force human approval before auto-commit is unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [dossier.store :as store]
            [dossier.operation :as op]))

(def analyst {:actor-id "an-1" :actor-role :analyst})
(def officer {:actor-id "co-1" :actor-role :compliance-officer})

(def clean-upsert
  {:op :record/upsert :subject "of-4" :entity-kind :official
   :patch {:id "of-4" :name "佐藤 三郎(デモ)" :title "監査役" :org "co-100" :capacity :officer
           :source {:class :official-registry :ref "houjin-bangou:demo"}}})

(def clean-relationship
  "`:to \"ag-1\"` (not \"co-200\") deliberately avoids the demo sanctions-flagged
  company, so this fixture exercises the phase gate alone, not the
  high-stakes gate (see policy_contract_test.clj for that)."
  {:op :relationship/draft :subject "rel-1" :from "co-100" :to "ag-1" :kind :regulatory-oversight
   :pct nil :source {:class :regulatory-filing :ref "demo"} :as-of "2026-01-01"})

(def clean-disclosure
  {:op :disclosure/query :subject "co-100" :company-id "co-100"})

(def correction-req
  {:op :correction/request :subject "co-100" :entity-kind :company
   :disputed-field :status :claim :inactive})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-upsert analyst)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (nil? (store/official s "of-4")) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "disclosure/query is a read → phase 0 lets it through (governor still applies)"
    (let [[_ res] (run 0 clean-disclosure {:actor-id "cl-1" :actor-role :client :tenant "tenant-basic"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-upsert
  (testing "a clean upsert that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 clean-upsert analyst)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-relationship-writes-under-approval
  (let [[_ res] (run 2 clean-relationship analyst)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-upsert
  (let [[s res] (run 3 clean-upsert analyst)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "佐藤 三郎(デモ)" (:name (store/official s "of-4"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (no source) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :relationship/draft :subject "rel-2" :from "co-100" :to "co-200"
                          :kind :joint-venture :pct nil :source nil :as-of "2026-01-01" :bias? true}
                       analyst)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest correction-request-never-auto-commits-at-any-phase
  (testing "a data-subject correction never reaches :commit without an explicit human :approval"
    (doseq [ph [0 1 2 3]]
      (let [[_ res] (run ph correction-req officer)]
        (is (not= :commit (get-in res [:state :disposition]))
            (str "phase " ph " must not auto-commit a correction"))))))
