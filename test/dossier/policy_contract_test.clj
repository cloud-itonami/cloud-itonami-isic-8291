(ns dossier.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-6310`'s policy_contract_test / robotaxi's
  safety_contract_test. The single invariant under test:

    Dossier-LLM never writes/discloses/resolves a record the
    DisclosureGovernor would reject, and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [dossier.store :as store]
            [dossier.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def analyst {:actor-id "an-1" :actor-role :analyst})
(def officer {:actor-id "co-1" :actor-role :compliance-officer})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-upsert-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :record/upsert :subject "of-4" :entity-kind :official
                   :patch {:id "of-4" :name "佐藤 三郎(デモ)" :title "監査役" :org "co-100"
                           :capacity :officer
                           :source {:class :official-registry :ref "houjin-bangou:demo"}}}
                  analyst)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "佐藤 三郎(デモ)" (:name (store/official db "of-4"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "a :client role has no upsert permission → HOLD, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :record/upsert :subject "of-4" :entity-kind :official
                     :patch {:id "of-4" :name "X" :title "T" :org "co-100" :capacity :officer
                             :source {:class :official-registry :ref "demo"}}}
                    {:actor-id "cl-1" :actor-role :client})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/official db "of-4")) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest uncited-relationship-is-held
  (testing "a relationship edge with no source citation (hallucination) → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :relationship/draft :subject "rel-1" :from "co-100" :to "co-200"
                     :kind :joint-venture :pct nil :source nil :as-of "2026-01-01" :bias? true}
                    analyst)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-basis} (-> (store/ledger db) first :basis)))
      (is (empty? (store/relationships-of db "co-100")) "no edge written"))))

(deftest private-field-in-upsert-is-held
  (testing "a proposal smuggling a schema-excluded private-life field → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :record/upsert :subject "of-4" :entity-kind :official
                     :patch {:id "of-4" :name "X" :title "T" :org "co-100" :capacity :officer
                             :source {:class :official-registry :ref "demo"}}
                     :leaky? true}
                    analyst)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:scope-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/official db "of-4"))))))

(deftest uncontracted-disclosure-is-held
  (testing "a disclosure query from a tenant with no registered contract → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :disclosure/query :subject "co-100" :company-id "co-100"}
                    {:actor-id "cl-2" :actor-role :client :tenant "tenant-ghost"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest over-disclosure-beyond-tier-is-held
  (testing "a disclosure query pulling columns beyond the contract's tier → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :disclosure/query :subject "co-100" :company-id "co-100" :greedy? true}
                    {:actor-id "cl-1" :actor-role :client :tenant "tenant-basic"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest screen-name-requires-compliance-tier
  (testing "a :tier/basic contract cannot run a name screen at all → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6b"
                    {:op :disclosure/screen-name :subject "tenant-basic" :name "Jane Smith (demo)"}
                    {:actor-id "cl-1" :actor-role :client :tenant "tenant-basic"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest screen-name-clean-official-commits-directly
  (testing "a clean, high-confidence, non-high-stakes name screen auto-serves (it's a governed read)"
    (let [[_db actor] (fresh)
          res (exec-op actor "t6c"
                    {:op :disclosure/screen-name :subject "tenant-acme" :name "山田 一郎(デモ)"}
                    {:actor-id "cl-3" :actor-role :client :tenant "tenant-acme"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest screen-name-hit-escalates-then-human-decides
  (testing "a name screen that hits a sanctions-flagged org interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t6d"
                   {:op :disclosure/screen-name :subject "tenant-acme" :name "Jane Smith (demo)"}
                   {:actor-id "cl-3" :actor-role :client :tenant "tenant-acme"})]
      (is (= :interrupted (:status r1)))
      (is (= :high-stakes (-> r1 :state :audit last :reason)))
      (let [r2 (g/run* actor {:approval {:status :approved :by "compliance-1"}}
                       {:thread-id "t6d" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :commit (-> (store/ledger db) last :disposition)))))))

(deftest sanctions-flagged-subject-escalates-then-human-decides
  (testing "disclosure targeting a sanctions-flagged company interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t7"
                   {:op :disclosure/query :subject "co-200" :company-id "co-200"}
                   {:actor-id "cl-3" :actor-role :client :tenant "tenant-acme"})]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (is (= :high-stakes (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "compliance-1"}}
                         {:thread-id "t7" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[_db actor] (fresh)
          _  (exec-op actor "t8"
                  {:op :disclosure/query :subject "co-200" :company-id "co-200"}
                  {:actor-id "cl-3" :actor-role :client :tenant "tenant-acme"})
          r2 (g/run* actor {:approval {:status :rejected :by "compliance-1"}}
                     {:thread-id "t8" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition]))))))

(deftest correction-request-always-escalates-regardless-of-confidence
  (testing "a data-subject correction request always reaches a human, never auto-resolves"
    (let [[db actor] (fresh)
          before (store/company db "co-100")
          r1 (exec-op actor "t9"
                   {:op :correction/request :subject "co-100" :entity-kind :company
                    :disputed-field :status :claim :inactive}
                   officer)]
      (is (= :interrupted (:status r1)))
      (is (= :data-subject-dispute (-> r1 :state :audit last :reason)))
      (testing "approve → commit applies the correction"
        (let [r2 (g/run* actor {:approval {:status :approved :by "compliance-1"}}
                         {:thread-id "t9" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :inactive (:status (store/company db "co-100"))))))
      (testing "a second, rejected dispute leaves the entity unchanged"
        (let [[db2 actor2] (fresh)
              _  (exec-op actor2 "t10"
                      {:op :correction/request :subject "co-100" :entity-kind :company
                       :disputed-field :status :claim :inactive}
                      officer)
              r3 (g/run* actor2 {:approval {:status :rejected :by "compliance-1"}}
                        {:thread-id "t10" :resume? true})]
          (is (= :hold (get-in r3 [:state :disposition])))
          (is (= (:status before) (:status (store/company db2 "co-100")))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :record/upsert :subject "of-4" :entity-kind :official
                          :patch {:id "of-4" :name "X" :title "T" :org "co-100" :capacity :officer
                                  :source {:class :official-registry :ref "demo"}}}
               analyst)
      (exec-op actor "b" {:op :relationship/draft :subject "rel-1" :from "co-100" :to "co-200"
                          :kind :joint-venture :pct nil :source nil :as-of "2026-01-01" :bias? true}
               analyst)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
