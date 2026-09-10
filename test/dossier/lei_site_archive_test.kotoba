(ns dossier.lei-site-archive-test
  "dossier.lei-site-archive exercised entirely offline via an injected
  fake fetch-fn, using realistic fixture EDN text matching the REAL
  ADR-2607182500 quad-log shape (spot-checked live against the actual
  cloud-itonami-lei-06btx5uwzd0gq5n5y745 repo before writing this test),
  same discipline as `dossier.commoncrawl-test`. No network access
  needed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [dossier.lei-site-archive :as lsa]))

(defn- fake-fetch [routes]
  (fn [path] (get routes path)))

(def wabtec-blueprint-text
  "{:company/legal-name \"Westinghouse Air Brake Technologies Corporation (demo)\"
   :company/lei \"06BTX5UWZD0GQ5N5Y745\"
   :company/jurisdiction \"US-DE\"
   :company/website \"https://www.wabteccorp.com\"
   :company/ticker \"WAB (NYSE)\"}")

(def wabtec-journal-text
  "[[\"westinghouse-site-1\" :site/url \"https://www.wabteccorp.com\" 1 :add]
 [\"westinghouse-site-1\" :site/checked-at \"2026-07-18\" 1 :add]
 [\"westinghouse-site-1\" :site/http-status 200 1 :add]
 [\"westinghouse-site-1\" :site/title \"Homepage | Wabtec Corporation\" 1 :add]
 [\"westinghouse-site-1\" :site/description \"Leading rail technology.\" 1 :add]]")

(def re-checked-journal-text
  "[[\"westinghouse-site-1\" :site/url \"https://www.wabteccorp.com\" 1 :add]
 [\"westinghouse-site-1\" :site/checked-at \"2026-07-18\" 1 :add]
 [\"westinghouse-site-1\" :site/http-status 200 1 :add]
 [\"westinghouse-site-1\" :site/title \"Homepage | Wabtec Corporation\" 1 :add]
 [\"westinghouse-site-2\" :site/url \"https://www.wabteccorp.com\" 2 :add]
 [\"westinghouse-site-2\" :site/checked-at \"2026-08-01\" 2 :add]
 [\"westinghouse-site-2\" :site/http-status 200 2 :add]
 [\"westinghouse-site-2\" :site/title \"Homepage | Wabtec Corporation (updated)\" 2 :add]]")

(def failure-journal-text
  "[[\"nasdaq-site-1\" :site/url \"https://www.nasdaq.com\" 1 :add]
 [\"nasdaq-site-1\" :site/checked-at \"2026-07-18\" 1 :add]
 [\"nasdaq-site-1\" :site/http-status 0 1 :add]]")

(defn- wabtec-routes []
  {"/cloud-itonami-lei-06btx5uwzd0gq5n5y745/main/blueprint.edn" wabtec-blueprint-text
   "/cloud-itonami-lei-06btx5uwzd0gq5n5y745/main/80-data/public/site.journal.edn" wabtec-journal-text
   "/cloud-itonami-lei-549300l8x1q78erxfd06/main/80-data/public/site.journal.edn" failure-journal-text})

(deftest configured?-is-always-true-keyless
  (is (true? (lsa/configured?))))

(deftest repo-name-of-handles-both-bare-lei-and-dossier-id-forms
  (is (= "cloud-itonami-lei-06btx5uwzd0gq5n5y745" (lsa/repo-name-of "06BTX5UWZD0GQ5N5Y745")))
  (is (= "cloud-itonami-lei-06btx5uwzd0gq5n5y745" (lsa/repo-name-of "lei-06BTX5UWZD0GQ5N5Y745")))
  (is (= "cloud-itonami-lei-06btx5uwzd0gq5n5y745" (lsa/repo-name-of "lei-06btx5uwzd0gq5n5y745"))
      "case-insensitive on input, lowercase repo name regardless"))

(deftest fetch-blueprint-parses-real-company-identity-shape
  (let [fetch (fake-fetch (wabtec-routes))
        bp (lsa/fetch-blueprint fetch "06BTX5UWZD0GQ5N5Y745")]
    (is (= "https://www.wabteccorp.com" (:company/website bp)))
    (is (= "US-DE" (:company/jurisdiction bp)))))

(deftest fetch-blueprint-on-unknown-lei-is-nil
  (let [fetch (fake-fetch (wabtec-routes))]
    (is (nil? (lsa/fetch-blueprint fetch "0000000000000000AAAA")))))

(deftest latest-site-observation-picks-the-last-entity-not-the-first
  (testing "a re-checked journal has TWO entity ids -- must fold the newest, not the original"
    (let [quads (edn/read-string re-checked-journal-text)
          obs (lsa/latest-site-observation quads)]
      (is (= "2026-08-01" (:site/checked-at obs)))
      (is (= "Homepage | Wabtec Corporation (updated)" (:site/title obs))))))

(deftest latest-site-observation-on-empty-journal-is-nil
  (is (nil? (lsa/latest-site-observation []))))

(deftest enrichment-for-a-real-success-case
  (let [fetch (fake-fetch (wabtec-routes))
        e (lsa/enrichment-for fetch "06BTX5UWZD0GQ5N5Y745")]
    (is (= "https://www.wabteccorp.com" (:domain e)))
    (is (true? (:has-web-presence? e)))
    (is (= "2026-07-18" (:latest-capture-date e)))
    (is (= 200 (:latest-capture-status e)))
    (is (= :public-web-crawl (get-in e [:source :class])))))

(deftest enrichment-for-a-real-failure-case-is-honest-not-fabricated
  (testing "a company whose site check failed (status 0) reports has-web-presence? false --
           never fabricated as true, matching the honest-degradation discipline ADR-2607182500
           itself established"
    (let [fetch (fake-fetch (wabtec-routes))
          e (lsa/enrichment-for fetch "549300L8X1Q78ERXFD06")]
      (is (false? (:has-web-presence? e)))
      (is (= 0 (:latest-capture-status e))))))

(deftest enrichment-for-a-lei-with-no-archive-repo-at-all-is-nil
  (testing "distinct from has-web-presence? false -- nil means 'this workspace never checked',
           false means 'we checked and it did not respond'"
    (let [fetch (fake-fetch (wabtec-routes))]
      (is (nil? (lsa/enrichment-for fetch "0000000000000000AAAA"))))))

(deftest enrichment-shape-matches-dossier-commoncrawl-enrichment-shape
  (testing "same keys as dossier.commoncrawl/->enrichment so a caller can treat either
           enrichment source interchangeably"
    (let [fetch (fake-fetch (wabtec-routes))
          e (lsa/enrichment-for fetch "06BTX5UWZD0GQ5N5Y745")]
      (is (= #{:domain :has-web-presence? :latest-capture-date :latest-capture-status :source}
             (set (keys e)))))))
