(ns dossier.facts-test
  "The R0 source catalog is the whole ground truth for the source-basis
  gate — these tests guard its own internal honesty (every class it
  advertises is actually backed by a catalog entry, no duplicate/aspirational
  entries)."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id name jurisdiction class covers access url]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? jurisdiction))
      (is (keyword? class))
      (is (set? covers))
      (is (keyword? access))
      (is (string? url)))))

(deftest allowed-source-classes-matches-catalog
  (is (= (into #{} (map :class facts/catalog)) facts/allowed-source-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :official-registry))
  (is (facts/class-allowed? :government-sanctions-list))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? :social-media)))
  (is (not (facts/class-allowed? nil))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    ;; the catalog is a handful of real sources, not "全世界" — this test
    ;; fails loudly if someone pads the catalog with unverifiable entries.
    (is (= (count facts/catalog) (:source-count c)))
    (is (<= (:source-count c) 20) "R0 catalog should stay small and citable, not bulk-padded")
    (is (contains? (:jurisdictions c) :jpn))
    (is (contains? (:covers c) :sanctions-pep))))

(deftest live-capable-jurisdictions-is-a-static-code-fact-not-a-runtime-key-check
  (testing "ADR-2607110400 addendum 5: only GBR has a real live client (dossier.companies-house) so far"
    (let [c (facts/coverage)]
      (is (= #{:gbr} (:live-capable-jurisdictions c)))
      (is (not (contains? (:live-capable-jurisdictions c) :jpn))
          "no live client exists for JPN yet, even though it's in the R0 catalog"))))
