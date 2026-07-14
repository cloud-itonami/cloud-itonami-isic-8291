(ns dossier.gleif-test
  "dossier.gleif exercised entirely offline via an injected fake fetch-fn
  (canned JSON:API-shaped maps mirroring the real GLEIF `/lei-records`
  response — the exact field paths were confirmed 2026-07-14 against the
  real production API, `curl 'https://api.gleif.org/api/v1/lei-records/
  HWUPKR0MPOU8FGXBT394'` and a `filter[entity.legalName]` search, before
  this client was written) — no network access needed, same discipline as
  `dossier.companies-house-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dossier.gleif :as gleif]))

(defn- fake-fetch
  "A minimal stand-in for `gleif/live-http-fn`: dispatches on `:path`
  (ignoring host entirely, since this never makes a real request)."
  [routes]
  (fn [{:keys [path query]}]
    (when-let [f (get routes path)]
      (f query))))

(def kaigan-record
  "Fictitious lei-record resource, shaped exactly like GLEIF's real JSON:API
  response (data.attributes.lei / .entity.legalName.name /
  .entity.legalAddress.country / .entity.status / .entity.registeredAs)."
  {:type "lei-records"
   :id "969500DEMO0USA00001A"
   :attributes
   {:lei "969500DEMO0USA00001A"
    :entity {:legalName {:name "Kaigan Freight Systems Inc (demo)" :language "en"}
             :legalAddress {:country "US" :city "Oakland (demo)"}
             :registeredAs "USDEMO0004"
             :jurisdiction "US-CA"
             :status "ACTIVE"}
    :registration {:status "ISSUED"}}})

(def unmapped-country-record
  "A record whose legalAddress.country is deliberately NOT in the static
  alpha-2 -> alpha-3 map, to prove an unmapped country degrades to nil
  rather than a fabricated jurisdiction."
  {:type "lei-records"
   :id "969500DEMO0XYZ00002A"
   :attributes
   {:lei "969500DEMO0XYZ00002A"
    :entity {:legalName {:name "Faraway Holdings Ltd (demo)" :language "en"}
             :legalAddress {:country "ZZ"}
             :registeredAs nil
             :status "ACTIVE"}
    :registration {:status "ISSUED"}}})

(defn- kaigan-routes []
  {"/lei-records"
   (fn [q]
     ;; GLEIF's real `filter[entity.legalName]` matches case-insensitively
     ;; (confirmed empirically 2026-07-14) -- this fake mirrors that so
     ;; `find-lei-by-name`'s own case-insensitive re-match can be tested.
     (when (= (str/lower-case (get q "filter[entity.legalName]" ""))
              (str/lower-case "Kaigan Freight Systems Inc (demo)"))
       {:data [kaigan-record
               {:type "lei-records" :id "969500DEMO0USA99999Z"
                :attributes {:lei "969500DEMO0USA99999Z"
                             :entity {:legalName {:name "Kaigan Freight Systems Inc (demo) — Unrelated" :language "en"}
                                      :legalAddress {:country "US"}
                                      :status "ACTIVE"}}}]}))
   "/lei-records/969500DEMO0USA00001A"
   (fn [_] {:data kaigan-record})
   "/lei-records/969500DEMO0XYZ00002A"
   (fn [_] {:data unmapped-country-record})})

(deftest configured?-is-always-true-no-key-needed
  (is (true? (gleif/configured?))))

(deftest find-lei-by-name-is-exact-match-only-case-insensitive
  (testing "GLEIF's own filter is fuzzy/full-text, so this client re-applies exact-match discipline"
    (let [fetch (fake-fetch (kaigan-routes))]
      (is (= "969500DEMO0USA00001A"
             (get-in (gleif/find-lei-by-name fetch "Kaigan Freight Systems Inc (demo)") [:attributes :lei])))
      (is (= "969500DEMO0USA00001A"
             (get-in (gleif/find-lei-by-name fetch "KAIGAN FREIGHT SYSTEMS INC (DEMO)") [:attributes :lei]))
          "case-insensitive: GLEIF stores names in varying case across records")
      (is (nil? (gleif/find-lei-by-name fetch "Kaigan Freight Systems Inc (demo) — Unrelated"))
          "sanity: this fixture's second result exists but was not queried for")
      (is (nil? (gleif/find-lei-by-name fetch "Some Other Company Ltd (demo)"))
          "no search result at all -> nil, never a guess"))))

(deftest lei-record-maps-to-dossier-company-shape
  (let [fetch (fake-fetch (kaigan-routes))
        c (gleif/->company (gleif/lei-record fetch "969500DEMO0USA00001A"))]
    (is (= "lei-969500DEMO0USA00001A" (:id c)))
    (is (= "Kaigan Freight Systems Inc (demo)" (:legal-name c)))
    (is (= :usa (:jurisdiction c)))
    (is (= "USDEMO0004" (:registration-no c)))
    (is (= :active (:status c)))
    (is (= "969500DEMO0USA00001A" (:lei c)))
    (is (= :official-registry (get-in c [:source :class])))))

(deftest unmapped-country-degrades-jurisdiction-to-nil-not-a-guess
  (let [fetch (fake-fetch (kaigan-routes))
        c (gleif/->company (gleif/lei-record fetch "969500DEMO0XYZ00002A"))]
    (is (= "Faraway Holdings Ltd (demo)" (:legal-name c)))
    (is (nil? (:jurisdiction c)))))

(deftest missing-record-or-transport-failure-returns-nil-never-throws
  (let [always-nil (fn [_] nil)]
    (is (nil? (gleif/search-by-name always-nil "anything")))
    (is (nil? (gleif/lei-record always-nil "anything")))
    (is (nil? (gleif/->company nil)))
    (is (nil? (gleif/find-lei-by-name always-nil "anything")))))
