(ns dossier.llm-test
  "Dossier-LLM proposal generation, unit-level (no governor/actor involved —
  that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.store :as store]
            [dossier.llm :as llm]))

(deftest upsert-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :record/upsert :subject "of-4" :entity-kind :official
                         :patch {:id "of-4" :name "X" :title "T" :org "co-100" :capacity :officer
                                 :source {:class :official-registry :ref "demo"}}})]
    (is (= :upsert-official (:effect p)))
    (is (= {:class :official-registry :ref "demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest leaky-upsert-proposal-contains-the-excluded-field
  (testing "the LLM layer does not filter — that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [db (store/seed-db)
          p (llm/infer db {:op :record/upsert :subject "of-4" :entity-kind :official
                           :patch {:id "of-4" :name "X" :org "co-100"
                                   :source {:class :official-registry :ref "demo"}}
                           :leaky? true})]
      (is (contains? (:value p) :home-address)))))

(deftest relationship-draft-without-source-is-still-high-confidence
  (testing "bias? proves the source-basis gate cannot rely on confidence as a proxy for groundedness"
    (let [db (store/seed-db)
          p (llm/infer db {:op :relationship/draft :subject "rel-1" :from "co-100" :to "co-200"
                           :kind :joint-venture :pct nil :source nil :as-of "2026-01-01" :bias? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85)))))

(deftest disclosure-proposal-flags-sanctioned-subject
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/query :subject "co-200" :company-id "co-200"})]
    (is (= :sanctions-flag (:stake p)))))

(deftest disclosure-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :disclosure/query :subject "co-100" :company-id "co-100"})
        greedy (llm/infer db {:op :disclosure/query :subject "co-100" :company-id "co-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))))

(deftest name-screen-finds-sanctioned-official-via-org-flag
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/screen-name :subject "tenant-acme" :name "Jane Smith (demo)"})]
    (is (true? (get-in p [:value :hit?])))
    (is (= :sanctions-flag (:stake p)))))

(deftest name-screen-finds-government-official-via-capacity
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/screen-name :subject "tenant-acme" :name "鈴木 次官(デモ)"})]
    (is (true? (get-in p [:value :hit?])))
    (is (= :sanctions-flag (:stake p)))))

(deftest name-screen-clean-official-is-no-hit
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/screen-name :subject "tenant-acme" :name "山田 一郎(デモ)"})]
    (is (false? (get-in p [:value :hit?])))
    (is (nil? (:stake p)))))

(deftest name-screen-unknown-name-is-not-found-but-high-confidence
  (testing "not-found is a definitive, actionable negative screening result against R0's
            catalog -- confidence reflects certainty in THAT match, not the catalog's
            coverage breadth (which is documented separately via facts/coverage). A low
            confidence here would make every screen of someone outside R0's narrow scope
            escalate for human review, defeating the point of an automatable screening op."
    (let [db (store/seed-db)
          p (llm/infer db {:op :disclosure/screen-name :subject "tenant-acme" :name "誰でもない人(デモ)"})]
      (is (false? (get-in p [:value :found?])))
      (is (false? (get-in p [:value :hit?])))
      (is (>= (:confidence p) 0.6)))))

(deftest correction-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :correction/request :subject "co-100" :entity-kind :company
                         :disputed-field :status :claim ":inactive"})]
    (is (= :correction-apply (:effect p)))
    (is (< (:confidence p) 0.9) "corrections are claims pending human verification, never auto-confident")))
