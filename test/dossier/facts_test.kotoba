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
    ;; Guard renegotiated 20 -> 21 (2026-07-14) for ONE argued addition,
    ;; :global-gleif-lei (dossier.gleif) — a real, citable, live-capable
    ;; supranational registry, not a bulk pad; see the catalog entry's own
    ;; comment for the case. The next add past 21 must argue its case too.
    (is (= (count facts/catalog) (:source-count c)))
    (is (<= (:source-count c) 21) "R0 catalog should stay small and citable, not bulk-padded")
    (is (contains? (:jurisdictions c) :jpn))
    (is (contains? (:covers c) :sanctions-pep))))

(deftest live-capable-jurisdictions-is-a-static-code-fact-not-a-runtime-key-check
  (testing "ADR-2607110400 addendum 5 + GLEIF addendum + SEC EDGAR addendum +
           ADR-2607182200 houjin-bangou addendum: GBR (Companies House), UN
           (GLEIF, supranational), USA (SEC EDGAR) and JPN (houjin-bangou) have
           real live client CODE so far — this is a static fact about what code
           exists, not proof any of their API keys/Application IDs are actually
           configured in a given environment (see each client's `configured?`
           for that runtime check; houjin-bangou's in particular still has no
           real Application ID exercised anywhere, per its own ns docstring)"
    (let [c (facts/coverage)]
      (is (= #{:gbr :jpn :un :usa} (:live-capable-jurisdictions c))))))
