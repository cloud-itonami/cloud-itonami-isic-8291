(ns dossier.store
  "SSoT for the corporate/compliance intelligence actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/dossier/store_contract_test.clj) — the actor, the DisclosureGovernor
  and the audit ledger never know which SSoT they run on.

  Entity shapes are deliberately narrow (ADR-2607110400): a company, an
  official/director/UBO/government-official IN THEIR PROFESSIONAL CAPACITY
  ONLY, a government agency, a relationship edge between two entities, and a
  licensing contract. There is NO field anywhere in this schema for
  private-life data (home address, family, health, political/religious
  opinion, sexual orientation, real-time location) — the scope boundary is
  structural, not a runtime filter someone could forget to call.

  The ledger stays append-only on every backend — 'who disclosed what to
  whom, on what contract, on what source basis' is always a query over an
  immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (company [s id])
  (company-by-name [s name] "exact-match lookup by :legal-name — resolves a name-only reference (e.g. a subsidiary a consumer only has by name) to a company id")
  (all-companies [s])
  (official [s id])
  (official-by-name [s name] "exact-match lookup by :name — the KYC/PEP screening query shape; no fuzzy matching in R0")
  (officials-of [s org-id] "officials whose :org is this company/agency id")
  (agency [s id])
  (relationships-of [s entity-id] "relationship edges touching this entity, either side")
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-companies [s companies] "replace/seed companies (map id→company)")
  (with-officials [s officials] "replace/seed officials (map id→official)")
  (with-agencies [s agencies]   "replace/seed agencies (map id→agency)")
  (with-relationships [s rels]  "replace/seed relationship edges (vector)")
  (with-contracts [s contracts] "replace/seed licensing contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real entities) ─────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline and
  no real company or person is ever named in this repository. `co-200` /
  `of-2` carry a demo :sanctions? flag purely to exercise the high-stakes
  governor gate — it is not a claim about any real entity."
  []
  {:companies
   {;; :lei is a demo Legal Entity Identifier (dossier.gleif's live-data
    ;; seam, ADR pending) -- "DEMO" embedded in the value makes it
    ;; unmistakably not a real GLEIF-issued LEI (a real LEI is 20 opaque
    ;; alphanumeric characters with an ISO 17442 check-digit suffix,
    ;; never a readable word). Companies can carry both a national
    ;; registry number AND an LEI -- they identify the same legal entity
    ;; to two different registries, not competing identifiers.
    "co-100" {:id "co-100" :legal-name "出島貿易株式会社(デモ)" :jurisdiction :jpn
              :registration-no "JPDEMO0001" :status :active
              :lei "969500DEMO0JPN00001A"
              :source {:class :official-registry :ref "houjin-bangou:demo"}
              :flags {}}
    "co-200" {:id "co-200" :legal-name "Northwind Capital Holdings Ltd (demo)"
              :jurisdiction :gbr :registration-no "GBDEMO0002" :status :active
              :source {:class :official-registry :ref "companies-house:demo"}
              :flags {:sanctions? true}}
    ;; a subsidiary whose OWN registry facts are clean, but whose ownership
    ;; chain (see :relationships below) traces to the sanctions-flagged
    ;; co-200 -- exists purely to exercise :disclosure/ownership-chain.
    "co-300" {:id "co-300" :legal-name "出島サブシディアリ株式会社(デモ)"
              :jurisdiction :jpn :registration-no "JPDEMO0003" :status :active
              :source {:class :official-registry :ref "houjin-bangou:demo"}
              :flags {}}}
   :officials
   {"of-1" {:id "of-1" :name "山田 一郎(デモ)" :title "代表取締役" :org "co-100"
            :capacity :director
            :source {:class :official-registry :ref "houjin-bangou:demo"}}
    "of-2" {:id "of-2" :name "Jane Smith (demo)" :title "Director" :org "co-200"
            :capacity :director
            :source {:class :official-registry :ref "companies-house:demo"}}
    "of-3" {:id "of-3" :name "鈴木 次官(デモ)" :title "審議官" :org "ag-1"
            :capacity :government-official
            :source {:class :official-registry :ref "demo-agency-directory"}}}
   :agencies
   {"ag-1" {:id "ag-1" :name "デモ規制当局" :jurisdiction :jpn :level :national}}
   :contracts
   {"tenant-acme" {:tenant "tenant-acme" :tier :tier/compliance :active? true
                   :purpose :vendor-due-diligence}
    "tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true
                    :purpose :registry-lookup}
    "tenant-graph" {:tenant "tenant-graph" :tier :tier/graph :active? true
                    :purpose :ownership-and-conflict-screening}}
   ;; Seeded relationship edges (ADR-2607110400 addendum 4): co-200 (sanctions-
   ;; flagged) owns 60% of co-300, and of-1 (co-100's director) ALSO sits on
   ;; co-200's board -- an undisclosed-conflict-shaped scenario. These exist
   ;; purely to exercise :disclosure/ownership-chain and :disclosure/
   ;; relationship-check; they do not touch co-100 as an edge endpoint, so
   ;; `(relationships-of "co-100")` stays empty as existing tests expect.
   :relationships
   [{:id "rel-co200-owns-co300" :from "co-200" :to "co-300" :kind :ownership
     :pct 60 :source {:class :official-registry :ref "companies-house:demo"}
     :as-of "2025-01-01"}
    {:id "rel-of1-director-co200" :from "of-1" :to "co-200" :kind :directorship
     :pct nil :source {:class :official-registry :ref "companies-house:demo"}
     :as-of "2025-06-01"}
    ;; a direct PERSON-to-PERSON edge (neither endpoint is a company) --
    ;; exercises :disclosure/relationship-check's :target-person-name path,
    ;; the shape cloud-itonami-isic-6621/6622's adjuster/broker-vs-claimant
    ;; conflict-of-interest checks need (relationships-of is entity-kind-
    ;; agnostic, so this needed no new edge shape, just a new resolution
    ;; path for the target side).
    {:id "rel-of2-business-contact-of1" :from "of-2" :to "of-1" :kind :business-contact
     :pct nil :source {:class :official-registry :ref "companies-house:demo"}
     :as-of "2025-08-01"}]})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (company [_ id] (get-in @a [:companies id]))
  (company-by-name [_ name] (first (filter #(= name (:legal-name %)) (vals (:companies @a)))))
  (all-companies [_] (sort-by :id (vals (:companies @a))))
  (official [_ id] (get-in @a [:officials id]))
  (official-by-name [_ name] (first (filter #(= name (:name %)) (vals (:officials @a)))))
  (officials-of [_ org-id]
    (->> (vals (:officials @a)) (filter #(= org-id (:org %))) (sort-by :id)))
  (agency [_ id] (get-in @a [:agencies id]))
  (relationships-of [_ entity-id]
    (->> (:relationships @a)
         (filter #(or (= entity-id (:from %)) (= entity-id (:to %))))
         (sort-by :id)))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :upsert-company  (swap! a update-in [:companies (:id value)] merge value)
      :upsert-official (swap! a update-in [:officials (:id value)] merge value)
      :upsert-agency   (swap! a update-in [:agencies (:id value)] merge value)
      :add-relationship (swap! a update :relationships (fnil conj []) value)
      :correction-apply  (swap! a update-in [(:kind value) (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-companies [s cs]     (when (seq cs) (swap! a assoc :companies cs)) s)
  (with-officials [s os]     (when (seq os) (swap! a assoc :officials os)) s)
  (with-agencies [s ags]     (when (seq ags) (swap! a assoc :agencies ags)) s)
  (with-relationships [s rs] (when (seq rs) (swap! a assoc :relationships rs)) s)
  (with-contracts [s cts]    (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data (including its seeded relationship
  edges — see `demo-data`). The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (source citations, flags, relationship payload, ledger
  facts) are stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities. `:official/org` is intentionally a plain string, not a ref —
  it may point at either a company or an agency, two different entity kinds."
  {:company/id      {:db/unique :db.unique/identity}
   :official/id     {:db/unique :db.unique/identity}
   :agency/id       {:db/unique :db.unique/identity}
   :relationship/id {:db/unique :db.unique/identity}
   :contract/tenant {:db/unique :db.unique/identity}
   :ledger/seq      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- company->tx [{:keys [id legal-name jurisdiction registration-no status source flags lei]}]
  (cond-> {:company/id id}
    legal-name       (assoc :company/legal-name legal-name)
    jurisdiction     (assoc :company/jurisdiction jurisdiction)
    registration-no  (assoc :company/registration-no registration-no)
    status           (assoc :company/status status)
    source           (assoc :company/source (enc source))
    lei              (assoc :company/lei lei)
    true             (assoc :company/flags (enc (or flags {})))))

(defn- pull->company [m]
  (when (:company/id m)
    {:id (:company/id m) :legal-name (:company/legal-name m)
     :jurisdiction (:company/jurisdiction m) :registration-no (:company/registration-no m)
     :status (:company/status m) :source (dec* (:company/source m))
     :lei (:company/lei m)
     :flags (or (dec* (:company/flags m)) {})}))

(def ^:private company-pull
  [:company/id :company/legal-name :company/jurisdiction :company/registration-no
   :company/status :company/source :company/flags :company/lei])

(defn- official->tx [{:keys [id name title org capacity source]}]
  (cond-> {:official/id id}
    name     (assoc :official/name name)
    title    (assoc :official/title title)
    org      (assoc :official/org org)
    capacity (assoc :official/capacity capacity)
    source   (assoc :official/source (enc source))))

(defn- pull->official [m]
  (when (:official/id m)
    {:id (:official/id m) :name (:official/name m) :title (:official/title m)
     :org (:official/org m) :capacity (:official/capacity m)
     :source (dec* (:official/source m))}))

(def ^:private official-pull
  [:official/id :official/name :official/title :official/org :official/capacity :official/source])

(defn- agency->tx [{:keys [id name jurisdiction level]}]
  (cond-> {:agency/id id}
    name         (assoc :agency/name name)
    jurisdiction (assoc :agency/jurisdiction jurisdiction)
    level        (assoc :agency/level level)))

(defn- pull->agency [m]
  (when (:agency/id m)
    {:id (:agency/id m) :name (:agency/name m)
     :jurisdiction (:agency/jurisdiction m) :level (:agency/level m)}))

(defn- relationship->tx [{:keys [id from to kind pct source as-of]}]
  {:relationship/id id :relationship/from from :relationship/to to
   :relationship/kind kind :relationship/payload (enc {:pct pct :source source :as-of as-of})})

(defn- pull->relationship [m]
  (let [p (dec* (:relationship/payload m))]
    {:id (:relationship/id m) :from (:relationship/from m) :to (:relationship/to m)
     :kind (:relationship/kind m) :pct (:pct p) :source (:source p) :as-of (:as-of p)}))

(def ^:private relationship-pull
  [:relationship/id :relationship/from :relationship/to :relationship/kind :relationship/payload])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull
  [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defrecord DatomicStore [conn]
  Store
  (company [_ id] (pull->company (d/pull (d/db conn) company-pull [:company/id id])))
  (company-by-name [_ name]
    (when-let [id (d/q '[:find ?id . :in $ ?name
                         :where [?c :company/legal-name ?name] [?c :company/id ?id]]
                       (d/db conn) name)]
      (pull->company (d/pull (d/db conn) company-pull [:company/id id]))))
  (all-companies [_]
    (->> (d/q '[:find [?id ...] :where [?e :company/id ?id]] (d/db conn))
         (map #(pull->company (d/pull (d/db conn) company-pull [:company/id %])))
         (sort-by :id)))
  (official [_ id] (pull->official (d/pull (d/db conn) official-pull [:official/id id])))
  (official-by-name [_ name]
    (when-let [id (d/q '[:find ?id . :in $ ?name
                         :where [?o :official/name ?name] [?o :official/id ?id]]
                       (d/db conn) name)]
      (pull->official (d/pull (d/db conn) official-pull [:official/id id]))))
  (officials-of [_ org-id]
    (->> (d/q '[:find [?id ...] :in $ ?org
                :where [?o :official/org ?org] [?o :official/id ?id]]
              (d/db conn) org-id)
         (map #(pull->official (d/pull (d/db conn) official-pull [:official/id %])))
         (sort-by :id)))
  (agency [_ id] (pull->agency (d/pull (d/db conn) [:agency/id :agency/name :agency/jurisdiction :agency/level] [:agency/id id])))
  (relationships-of [_ entity-id]
    (->> (d/q '[:find [?id ...] :in $ ?eid
                :where (or [?r :relationship/from ?eid] [?r :relationship/to ?eid])
                       [?r :relationship/id ?id]]
              (d/db conn) entity-id)
         (map #(pull->relationship (d/pull (d/db conn) relationship-pull [:relationship/id %])))
         (sort-by :id)))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :upsert-company    (d/transact! conn [(company->tx value)])
      :upsert-official   (d/transact! conn [(official->tx value)])
      :upsert-agency     (d/transact! conn [(agency->tx value)])
      :add-relationship  (d/transact! conn [(relationship->tx value)])
      :correction-apply  (case (:kind value)
                           :companies (d/transact! conn [(company->tx (merge (company s (first path)) (:patch value)))])
                           :officials (d/transact! conn [(official->tx (merge (official s (first path)) (:patch value)))])
                           :agencies  (d/transact! conn [(agency->tx (merge (agency s (first path)) (:patch value)))])
                           nil)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-companies [s cs]
    (when (seq cs) (d/transact! conn (mapv company->tx (vals cs)))) s)
  (with-officials [s os]
    (when (seq os) (d/transact! conn (mapv official->tx (vals os)))) s)
  (with-agencies [s ags]
    (when (seq ags) (d/transact! conn (mapv agency->tx (vals ags)))) s)
  (with-relationships [s rs]
    (when (seq rs) (d/transact! conn (mapv relationship->tx rs))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [companies officials agencies relationships contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-companies companies) (with-officials officials)
         (with-agencies agencies) (with-relationships relationships)
         (with-contracts contracts)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
