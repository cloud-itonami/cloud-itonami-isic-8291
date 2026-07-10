(ns dossier.companies-house-test
  "dossier.companies-house exercised entirely offline via an injected fake
  fetch-fn (canned JSON-shaped maps mimicking the real Companies House API)
  — no real API key or network access needed, same discipline as
  langchain.jvm's tests injecting a fake :http-fn instead of hitting a live
  provider."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.companies-house :as ch]))

(defn- fake-fetch
  "A minimal stand-in for `ch/live-http-fn`: dispatches on `:path`
  (ignoring host/auth entirely, since this never makes a real request)."
  [routes]
  (fn [{:keys [path query]}]
    (when-let [f (get routes path)]
      (f query))))

(def northwind-profile
  {:company_name "Northwind Capital Holdings Ltd (demo)"
   :company_number "GBDEMO0002"
   :company_status "active"})

(def northwind-officers
  {:items [{:name "SMITH, Jane" :officer_role "director"
            :links {:officer {:appointments "/officers/off-jane-smith/appointments"}}}
           {:name "DOE, John" :officer_role "secretary" :links {}}]})

(defn- northwind-routes []
  {"/search/companies"
   (fn [{:keys [q]}]
     (when (= q "Northwind Capital Holdings Ltd (demo)")
       {:items [{:company_number "GBDEMO0002" :title "Northwind Capital Holdings Ltd (demo)"
                 :company_status "active"}
                {:company_number "GBDEMO9999" :title "Northwind Capital Holdings (unrelated)"
                 :company_status "active"}]}))
   "/company/GBDEMO0002"
   (fn [_] northwind-profile)
   "/company/GBDEMO0002/officers"
   (fn [_] northwind-officers)})

(deftest configured?-reflects-whether-a-key-is-present
  (is (false? (ch/configured? nil)))
  (is (false? (ch/configured? "")))
  (is (true? (ch/configured? "some-key"))))

(deftest find-company-by-name-is-exact-match-only
  (testing "the fuzzy/ranked search must not let a similarly-named company through"
    (let [fetch (fake-fetch (northwind-routes))]
      (is (= "GBDEMO0002" (:company_number (ch/find-company-by-name fetch "Northwind Capital Holdings Ltd (demo)"))))
      (is (nil? (ch/find-company-by-name fetch "Northwind Capital Holdings (unrelated)"))
          "sanity: this fixture's second result exists but was not queried for")
      (is (nil? (ch/find-company-by-name fetch "Some Other Company Ltd"))
          "no search result at all -> nil, never a guess"))))

(deftest company-profile-maps-to-dossier-company-shape
  (let [fetch (fake-fetch (northwind-routes))
        c (ch/->company (ch/company-profile fetch "GBDEMO0002"))]
    (is (= "gbr-GBDEMO0002" (:id c)))
    (is (= "Northwind Capital Holdings Ltd (demo)" (:legal-name c)))
    (is (= :gbr (:jurisdiction c)))
    (is (= :active (:status c)))
    (is (= :official-registry (get-in c [:source :class])))))

(deftest company-officers-map-to-dossier-official-shape
  (let [fetch (fake-fetch (northwind-routes))
        officers (mapv #(ch/->official "gbr-GBDEMO0002" "GBDEMO0002" %)
                       (ch/company-officers fetch "GBDEMO0002"))]
    (is (= 2 (count officers)))
    (is (= "/officers/off-jane-smith/appointments" (:id (first officers)))
        "a stable CH-provided id is preferred when the appointments link is present")
    (is (= :director (:capacity (first officers))))
    (is (= "gbr-officer-GBDEMO0002-" (subs (:id (second officers)) 0 (count "gbr-officer-GBDEMO0002-")))
        "falls back to a derived id when the officer entry has no appointments link")
    (is (= :officer (:capacity (second officers))))))

(deftest missing-key-or-transport-failure-returns-nil-never-throws
  (let [always-nil (fn [_] nil)]
    (is (nil? (ch/search-companies always-nil "anything")))
    (is (nil? (ch/company-profile always-nil "GBDEMO0002")))
    (is (nil? (ch/->company nil)))
    (is (empty? (mapv identity (or (ch/company-officers always-nil "GBDEMO0002") []))))))
