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

(deftest ownership-chain-finds-flagged-owner
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/ownership-chain :subject "tenant-graph" :company-id "co-300"})]
    (is (true? (get-in p [:value :has-sourced-ownership-data?])))
    (is (= ["co-200"] (mapv :owner-id (get-in p [:value :owners]))))
    (is (= :sanctions-flag (:stake p)))))

(deftest ownership-chain-resolves-by-company-name
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/ownership-chain :subject "tenant-graph"
                         :company-name "出島サブシディアリ株式会社(デモ)"})]
    (is (= "co-300" (get-in p [:value :company-id])))
    (is (true? (get-in p [:value :has-sourced-ownership-data?])))))

(deftest ownership-chain-no-sourced-data-is-not-a-clean-verdict
  (testing "no ownership edge on file != no beneficial owners exist -- just uncorroborated"
    (let [db (store/seed-db)
          p (llm/infer db {:op :disclosure/ownership-chain :subject "tenant-graph" :company-id "co-100"})]
      (is (false? (get-in p [:value :has-sourced-ownership-data?])))
      (is (empty? (get-in p [:value :owners])))
      (is (nil? (:stake p))))))

(deftest ownership-chain-unknown-company-is-not-found
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/ownership-chain :subject "tenant-graph" :company-id "co-999"})]
    (is (nil? (get-in p [:value :company-id])))
    (is (empty? (get-in p [:value :owners])))))

(deftest relationship-check-finds-org-membership
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/relationship-check :subject "tenant-graph"
                         :person-name "Jane Smith (demo)" :company-id "co-200"})]
    (is (true? (get-in p [:value :related?])))
    (is (= :org-membership (get-in p [:value :kind])))
    (is (= :sanctions-flag (:stake p)) "co-200 itself is sanctions-flagged")))

(deftest relationship-check-finds-directorship-edge-not-org-membership
  (testing "of-1's :org is co-100, but a separate directorship EDGE connects him to co-200 too"
    (let [db (store/seed-db)
          p (llm/infer db {:op :disclosure/relationship-check :subject "tenant-graph"
                           :person-name "山田 一郎(デモ)" :company-id "co-200"})]
      (is (true? (get-in p [:value :related?])))
      (is (= :directorship (get-in p [:value :kind]))))))

(deftest relationship-check-no-relationship-is-clean-not-escalated
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/relationship-check :subject "tenant-graph"
                         :person-name "山田 一郎(デモ)" :company-id "co-300"})]
    (is (false? (get-in p [:value :related?])))
    (is (nil? (:stake p)))
    (is (>= (:confidence p) 0.9))))

(deftest relationship-check-person-to-person-via-target-person-name
  (testing "the target can be another named person (not a company) -- the adjuster/broker-vs-
            claimant conflict-of-interest shape cloud-itonami-isic-6621/6622 need"
    (let [db (store/seed-db)
          p (llm/infer db {:op :disclosure/relationship-check :subject "tenant-graph"
                           :person-name "Jane Smith (demo)" :target-person-name "山田 一郎(デモ)"})]
      (is (true? (get-in p [:value :related?])))
      (is (= :business-contact (get-in p [:value :kind]))))))

(deftest relationship-check-target-name-resolves-to-company
  (testing ":target-name is for a caller who doesn't know whether its counterparty is a company
            or a person (e.g. cloud-itonami-isic-6621/6622's generic party records) -- it tries
            company-by-name first"
    (let [db (store/seed-db)
          p (llm/infer db {:op :disclosure/relationship-check :subject "tenant-graph"
                           :person-name "Jane Smith (demo)" :target-name "Northwind Capital Holdings Ltd (demo)"})]
      (is (true? (get-in p [:value :related?])))
      (is (= :org-membership (get-in p [:value :kind]))))))

(deftest relationship-check-target-name-falls-back-to-person
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/relationship-check :subject "tenant-graph"
                         :person-name "Jane Smith (demo)" :target-name "山田 一郎(デモ)"})]
    (is (true? (get-in p [:value :related?])))
    (is (= :business-contact (get-in p [:value :kind])))))

(deftest relationship-check-unrelated-person-to-person-is-clean
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/relationship-check :subject "tenant-graph"
                         :person-name "鈴木 次官(デモ)" :target-person-name "山田 一郎(デモ)"})]
    (is (false? (get-in p [:value :related?])))))

(deftest relationship-check-unknown-person-is-not-found
  (let [db (store/seed-db)
        p (llm/infer db {:op :disclosure/relationship-check :subject "tenant-graph"
                         :person-name "誰でもない人(デモ)" :company-id "co-100"})]
    (is (false? (get-in p [:value :found?])))
    (is (false? (get-in p [:value :related?])))))

(deftest correction-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :correction/request :subject "co-100" :entity-kind :company
                         :disputed-field :status :claim ":inactive"})]
    (is (= :correction-apply (:effect p)))
    (is (< (:confidence p) 0.9) "corrections are claims pending human verification, never auto-confident")))
