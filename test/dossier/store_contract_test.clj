(ns dossier.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "出島貿易株式会社(デモ)" (:legal-name (store/company s "co-100"))))
      (is (= {:class :official-registry :ref "houjin-bangou:demo"}
             (:source (store/company s "co-100")))
          "source citation round-trips (stored as EDN on Datomic, not a sub-entity)")
      (is (= {:sanctions? true} (:flags (store/company s "co-200"))))
      (is (= "co-100" (:org (store/official s "of-1"))))
      (is (= ["of-1"] (mapv :id (store/officials-of s "co-100"))))
      (is (= "of-2" (:id (store/official-by-name s "Jane Smith (demo)"))))
      (is (nil? (store/official-by-name s "誰でもない人(デモ)")))
      (is (= "co-300" (:id (store/company-by-name s "出島サブシディアリ株式会社(デモ)"))))
      (is (nil? (store/company-by-name s "存在しない法人(デモ)")))
      (is (= :government-official (:capacity (store/official s "of-3"))))
      (is (= "デモ規制当局" (:name (store/agency s "ag-1"))))
      (is (= 3 (count (store/all-companies s)))
          "co-100, co-200, and the seeded co-300 subsidiary")
      (is (= 2 (count (store/relationships-of s "co-200")))
          "seeded: co-200 owns co-300, AND of-1 sits on co-200's board")
      (is (= 1 (count (store/relationships-of s "co-300")))
          "seeded: owned 60% by co-200")
      (is (empty? (store/relationships-of s "co-100"))
          "co-100 is untouched by any seeded edge"))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial company upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :upsert-company
                                 :value {:id "co-100" :status :dissolved}})
        (is (= :dissolved (:status (store/company s "co-100"))))
        (is (= "出島貿易株式会社(デモ)" (:legal-name (store/company s "co-100"))) "legal-name preserved"))
      (testing "relationship edges commit and read back from both sides"
        (store/commit-record! s {:effect :add-relationship
                                 :value {:id "co-100-co-200-joint-venture" :from "co-100" :to "co-200"
                                         :kind :joint-venture :pct nil
                                         :source {:class :regulatory-filing :ref "demo"} :as-of "2026-01-01"}})
        (is (= 1 (count (store/relationships-of s "co-100")))
            "co-100 had zero seeded edges, plus this one new commit")
        (is (= 3 (count (store/relationships-of s "co-200")))
            "co-200 had 2 seeded edges (owns co-300, of-1 directorship), plus this one new commit"))
      (testing "correction-apply patches the target entity"
        (store/commit-record! s {:effect :correction-apply
                                 :value {:kind :companies :patch {:status :active}}
                                 :path ["co-100"]})
        (is (= :active (:status (store/company s "co-100")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest contract-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/compliance (:tier (store/contract s "tenant-acme"))))
      (is (true? (:active? (store/contract s "tenant-acme"))))
      (is (nil? (store/contract s "tenant-ghost"))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/company s "nope")))
    (is (= [] (store/all-companies s)))
    (is (= [] (store/ledger s)))
    (store/with-companies s {"x" {:id "x" :legal-name "X" :jurisdiction :jpn}})
    (is (= "X" (:legal-name (store/company s "x"))))))
