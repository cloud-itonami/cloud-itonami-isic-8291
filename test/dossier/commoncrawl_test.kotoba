(ns dossier.commoncrawl-test
  "dossier.commoncrawl exercised entirely offline via an injected fake
  fetch-fn (canned capture-record vectors mimicking the real Common
  Crawl Index response shapes, including the real 404-with-JSON-body
  no-match quirk this session's live verification actually found and
  fixed — see the ns docstring), same discipline as
  `dossier.companies-house-test`. No network access needed."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.commoncrawl :as cc]))

(defn- fake-fetch
  "A minimal stand-in for `cc/live-http-fn`: dispatches on `[:path
  (get-in query [\"url\"])]`, returning an already-parsed capture-records
  vector -- the same seam `live-http-fn` hands to `captures-of` after
  parsing the real newline-delimited-JSON / 404-JSON body."
  [routes]
  (fn [{:keys [path query]}]
    (get routes [path (get query "url")])))

(def example-capture
  {:urlkey "com,example)/" :url "https://www.example.com/" :timestamp "20260618163626"
   :status "200" :mime "text/html" :digest "KFMR3RACAHZZH2HGKDPO3FQODMO3XIJ7"})

(def example-capture-older
  {:urlkey "com,example)/" :url "https://www.example.com/" :timestamp "20260101000000"
   :status "200" :mime "text/html" :digest "OLDDIGEST"})

(def no-match-response
  [{:message "No Captures found for: nonexistent-domain.jp"}])

(defn- yamato-routes []
  {["/CC-MAIN-2026-25-index" "yamato-shokugyou.example.jp"] [example-capture-older example-capture]
   ["/CC-MAIN-2026-25-index" "nonexistent-domain.jp"] no-match-response})

(deftest configured?-is-always-true-keyless
  (is (true? (cc/configured?))))

(deftest latest-collection-id-reads-the-first-collection-in-the-list
  (is (= "CC-MAIN-2026-25" (cc/latest-collection-id (fn [] [{:id "CC-MAIN-2026-25"} {:id "CC-MAIN-2026-21"}]))))
  (is (nil? (cc/latest-collection-id (fn [] nil))) "a transport failure degrades to nil, never a stale guess"))

(deftest captures-of-real-404-no-match-quirk-returns-empty-vector-not-nil
  (testing "verified live in this session: a genuine no-match is HTTP 404 WITH a real JSON body,
           not a bare error -- captures-of must tell 'confirmed absent' ([]) apart from
           'transport failed, we don't know' (nil)"
    (let [fetch (fake-fetch (yamato-routes))]
      (is (= [] (cc/captures-of fetch "CC-MAIN-2026-25" "nonexistent-domain.jp"))))))

(deftest captures-of-transport-failure-returns-nil-never-an-empty-vector
  (let [always-nil (fn [_] nil)]
    (is (nil? (cc/captures-of always-nil "CC-MAIN-2026-25" "anything.jp"))
        "nil (unknown) must never be conflated with [] (confirmed absent)")))

(deftest captures-of-returns-every-real-match
  (let [fetch (fake-fetch (yamato-routes))]
    (is (= 2 (count (cc/captures-of fetch "CC-MAIN-2026-25" "yamato-shokugyou.example.jp"))))))

(deftest latest-capture-picks-the-newest-timestamp-not-just-the-first-result
  (let [fetch (fake-fetch (yamato-routes))
        c (cc/latest-capture fetch "CC-MAIN-2026-25" "yamato-shokugyou.example.jp")]
    (is (= "20260618163626" (:timestamp c))
        "the fixture lists the OLDER capture first -- this must not just take (first result)")))

(deftest latest-capture-on-no-match-is-nil
  (let [fetch (fake-fetch (yamato-routes))]
    (is (nil? (cc/latest-capture fetch "CC-MAIN-2026-25" "nonexistent-domain.jp")))))

(deftest has-web-presence?-true-for-a-real-match-false-for-a-confirmed-no-match
  (let [fetch (fake-fetch (yamato-routes))]
    (is (true? (cc/has-web-presence? fetch "CC-MAIN-2026-25" "yamato-shokugyou.example.jp")))
    (is (false? (cc/has-web-presence? fetch "CC-MAIN-2026-25" "nonexistent-domain.jp")))))

(deftest ->enrichment-is-never-a-dossier-store-company-shape
  (testing "no :id / :legal-name / :jurisdiction -- this can never be mistaken for a registry
           fact by a caller who mixes up mapper functions (ns docstring's core discipline)"
    (let [e (cc/->enrichment "yamato-shokugyou.example.jp" example-capture)]
      (is (nil? (:id e)))
      (is (nil? (:legal-name e)))
      (is (nil? (:jurisdiction e)))
      (is (true? (:has-web-presence? e)))
      (is (= "20260618163626" (:latest-capture-date e)))
      (is (= :public-web-crawl (get-in e [:source :class]))))))

(deftest ->enrichment-on-nil-capture-is-honestly-negative-not-omitted
  (let [e (cc/->enrichment "nonexistent-domain.jp" nil)]
    (is (false? (:has-web-presence? e)))
    (is (nil? (:latest-capture-date e)))))
