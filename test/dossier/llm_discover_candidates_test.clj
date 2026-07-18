(ns dossier.llm-discover-candidates-test
  "Test :disclosure/discover-candidates proposal generator."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.llm :as llm]))

(deftest discover-candidates-proposal-test
  (testing "Discover-candidates proposal with ISIC vertical"
    (let [req {:op :disclosure/discover-candidates
               :vertical {:isic "0610"} :count 3}
          prop (llm/infer {} req)]
      (is (contains? prop :summary) "proposal should have summary")
      (is (contains? prop :value) "proposal should have value")
      (is (= :discover-candidates (:effect prop)) "effect should be discover-candidates")
      (is (= 0.95 (:confidence prop)) "confidence should be high (0.95)")))

  (testing "Discover-candidates proposal with country vertical"
    (let [req {:op :disclosure/discover-candidates
               :vertical {:country "USA"} :count 2}
          prop (llm/infer {} req)]
      (is (contains? prop :source) "proposal should cite source")
      (is (not-empty (get-in prop [:value :candidates])) "should return candidates")))

  (testing "Governor source-basis check: proposal has citation"
    (let [req {:op :disclosure/discover-candidates
               :vertical {:isic "0610"}}
          prop (llm/infer {} req)]
      (is (seq (:cites prop)) "proposal should cite sources"))))
