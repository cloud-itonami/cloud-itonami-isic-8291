(ns dossier.live-store-test
  "dossier.live-store's decorator contract: local always wins when present,
  a live Companies House and/or GLEIF fallback only fires for names/ids
  local has nothing for, and a nil fetch-fn degrades to exactly the
  undecorated local store — entirely offline via injected fake fetch-fns."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dossier.store :as store]
            [dossier.live-store :as live]))

(defn- fake-fetch [routes]
  (fn [{:keys [path query]}]
    (when-let [f (get routes path)]
      (f query))))

(def acme-routes
  {"/search/companies"
   (fn [{:keys [q]}]
     (when (= q "Acme Registered Ltd (demo)")
       {:items [{:company_number "GBDEMO7777" :title "Acme Registered Ltd (demo)"
                 :company_status "active"}]}))
   "/company/GBDEMO7777"
   (fn [_] {:company_name "Acme Registered Ltd (demo)" :company_number "GBDEMO7777"
            :company_status "active"})
   "/company/GBDEMO7777/officers"
   (fn [_] {:items [{:name "Officer, Live (demo)" :officer_role "director" :links {}}]})})

(def zenith-lei-record
  {:type "lei-records" :id "969500DEMO0ZEN00001A"
   :attributes {:lei "969500DEMO0ZEN00001A"
                :entity {:legalName {:name "Zenith Trading Co (demo)" :language "en"}
                         :legalAddress {:country "US"}
                         :registeredAs "USDEMO9001"
                         :status "ACTIVE"}}})

(def zenith-lei-routes
  {"/lei-records"
   (fn [q]
     (when (= (str/lower-case (get q "filter[entity.legalName]" ""))
              (str/lower-case "Zenith Trading Co (demo)"))
       {:data [zenith-lei-record]}))
   "/lei-records/969500DEMO0ZEN00001A"
   (fn [_] {:data zenith-lei-record})})

(deftest nil-fetch-fn-behaves-exactly-like-the-undecorated-local-store
  (let [local (store/seed-db)
        decorated (live/live-store local nil)]
    (is (= (store/company local "co-100") (store/company decorated "co-100")))
    (is (= (store/company-by-name local "誰もいない(デモ)") (store/company-by-name decorated "誰もいない(デモ)")))
    (is (nil? (store/company-by-name decorated "Acme Registered Ltd (demo)"))
        "no fetch-fn -> no live fallback at all, even for a plausible-looking name")))

(deftest local-data-always-wins-over-a-live-fallback
  (testing "co-100 exists locally; a fetch-fn that would answer differently must never be consulted"
    (let [local (store/seed-db)
          suspicious-fetch (fn [_] (throw (ex-info "should never be called for a local hit" {})))
          decorated (live/live-store local suspicious-fetch)]
      (is (= (store/company local "co-100") (store/company decorated "co-100")))
      (is (= (store/company-by-name local "出島貿易株式会社(デモ)")
             (store/company-by-name decorated "出島貿易株式会社(デモ)"))))))

(deftest live-fallback-fires-for-a-name-local-has-nothing-for
  (let [local (store/seed-db)
        decorated (live/live-store local (fake-fetch acme-routes))
        c (store/company-by-name decorated "Acme Registered Ltd (demo)")]
    (is (= "gbr-GBDEMO7777" (:id c)))
    (is (= :gbr (:jurisdiction c)))
    (is (nil? (store/company-by-name local "Acme Registered Ltd (demo)"))
        "sanity: genuinely absent from local, so this really did come from the live fallback")))

(deftest live-fallback-fires-for-a-gbr-namespaced-id-local-has-nothing-for
  (let [local (store/seed-db)
        decorated (live/live-store local (fake-fetch acme-routes))]
    (is (= "Acme Registered Ltd (demo)" (:legal-name (store/company decorated "gbr-GBDEMO7777"))))
    (is (nil? (store/company local "gbr-GBDEMO7777")))))

(deftest officials-of-live-fallback-fires-only-when-local-has-none
  (let [local (store/seed-db)
        decorated (live/live-store local (fake-fetch acme-routes))]
    (testing "co-100 has a local official (of-1) -> local wins, live never consulted"
      (is (= (store/officials-of local "co-100") (store/officials-of decorated "co-100"))))
    (testing "gbr-GBDEMO7777 has no local officials at all -> live fallback fires"
      (let [officers (store/officials-of decorated "gbr-GBDEMO7777")]
        (is (= 1 (count officers)))
        (is (= "Officer, Live (demo)" (:name (first officers))))
        (is (= :director (:capacity (first officers))))))))

(deftest non-gbr-id-never-triggers-a-live-lookup
  (let [local (store/seed-db)
        decorated (live/live-store local (fake-fetch acme-routes))]
    (is (nil? (store/company decorated "jpn-does-not-exist"))
        "an id with no gbr- prefix is never sent to the live fetch-fn")))

(deftest writes-and-contracts-and-ledger-delegate-straight-through
  (let [local (store/seed-db)
        decorated (live/live-store local (fake-fetch acme-routes))]
    (store/commit-record! decorated {:effect :upsert-company :value {:id "co-100" :status :dissolved}})
    (is (= :dissolved (:status (store/company local "co-100")))
        "the decorator's commit-record! actually wrote through to the SAME underlying local store")
    (is (= (store/contract local "tenant-acme") (store/contract decorated "tenant-acme")))
    (store/append-ledger! decorated {:op :a :disposition :commit})
    (is (= (store/ledger local) (store/ledger decorated)))))

;; ───────────────────────── GLEIF (LiveLeiStore / lei-store) ─────────────

(deftest lei-store-nil-fetch-fn-behaves-exactly-like-the-undecorated-local-store
  (let [local (store/seed-db)
        decorated (live/lei-store local nil)]
    (is (= (store/company local "co-100") (store/company decorated "co-100")))
    (is (nil? (store/company-by-name decorated "Zenith Trading Co (demo)"))
        "no fetch-fn -> no live fallback at all")))

(deftest lei-store-local-data-always-wins-over-a-live-fallback
  (let [local (store/seed-db)
        suspicious-fetch (fn [_] (throw (ex-info "should never be called for a local hit" {})))
        decorated (live/lei-store local suspicious-fetch)]
    (is (= (store/company local "co-100") (store/company decorated "co-100")))
    (is (= "969500DEMO0JPN00001A" (:lei (store/company decorated "co-100")))
        "co-100's seeded demo :lei round-trips through the decorator untouched")))

(deftest lei-store-live-fallback-fires-for-a-name-local-has-nothing-for
  (let [local (store/seed-db)
        decorated (live/lei-store local (fake-fetch zenith-lei-routes))
        c (store/company-by-name decorated "Zenith Trading Co (demo)")]
    (is (= "lei-969500DEMO0ZEN00001A" (:id c)))
    (is (= :usa (:jurisdiction c)))
    (is (nil? (store/company-by-name local "Zenith Trading Co (demo)"))
        "sanity: genuinely absent from local, so this really did come from the live fallback")))

(deftest lei-store-live-fallback-fires-for-a-lei-namespaced-id-local-has-nothing-for
  (let [local (store/seed-db)
        decorated (live/lei-store local (fake-fetch zenith-lei-routes))]
    (is (= "Zenith Trading Co (demo)" (:legal-name (store/company decorated "lei-969500DEMO0ZEN00001A"))))
    (is (nil? (store/company local "lei-969500DEMO0ZEN00001A")))))

(deftest lei-store-officials-of-is-always-a-passthrough-gleif-has-no-officer-data
  (let [local (store/seed-db)
        decorated (live/lei-store local (fake-fetch zenith-lei-routes))]
    (is (= (store/officials-of local "co-100") (store/officials-of decorated "co-100")))
    (is (empty? (store/officials-of decorated "lei-969500DEMO0ZEN00001A"))
        "GLEIF has no officer endpoint at all -- never a live lookup here")))

(deftest non-lei-id-never-triggers-a-gleif-live-lookup
  (let [local (store/seed-db)
        decorated (live/lei-store local (fake-fetch zenith-lei-routes))]
    (is (nil? (store/company decorated "gbr-does-not-exist"))
        "an id with no lei- prefix is never sent to the GLEIF fetch-fn")))

;; ─────────────── combined chain: local -> GLEIF -> Companies House ──────

(deftest full-live-store-chains-both-sources-local-still-wins
  (let [local (store/seed-db)
        decorated (live/live-store local (fake-fetch acme-routes) (fake-fetch zenith-lei-routes))]
    (is (= (store/company local "co-100") (store/company decorated "co-100"))
        "local wins over both live sources")
    (testing "GLEIF fallback fires for a name only GLEIF's fixture knows"
      (is (= "lei-969500DEMO0ZEN00001A" (:id (store/company-by-name decorated "Zenith Trading Co (demo)")))))
    (testing "Companies House fallback fires for a name only CH's fixture knows"
      (is (= "gbr-GBDEMO7777" (:id (store/company-by-name decorated "Acme Registered Ltd (demo)")))))
    (testing "a name neither live source nor local knows about is still nil"
      (is (nil? (store/company-by-name decorated "Nobody Registered Anywhere (demo)"))))))

(deftest full-live-store-2-arg-arity-stays-companies-house-only-unchanged
  (testing "the 2-arg [local ch-fetch-fn] arity must NOT gain a GLEIF layer -- this is the exact
           shape the rest of this test file's CH-only tests above already exercise"
    (let [local (store/seed-db)
          decorated (live/live-store local (fake-fetch acme-routes))]
      (is (nil? (store/company-by-name decorated "Zenith Trading Co (demo)"))
          "no lei-fetch-fn was passed, so a GLEIF-only name is not found via this arity"))))
