(ns dossier.facts
  "R0 source-basis catalog — the ONLY source classes/references the
  DisclosureGovernor will accept as a citation for a company/official/
  relationship fact (ADR-2607110400 §4). Honesty over coverage, same
  discipline as `cloud-itonami-M6910`'s 10-jurisdiction R0: every entry here
  is a real, public, free (or already-licensed-in-principle) primary source.
  Adding coverage means adding a citable, real catalog entry — never
  fabricating one.")

(def catalog
  "Each entry: {:id :name :jurisdiction :class :covers :access :url}.
  :class is the value that must appear in a fact's `:source :class` for the
  DisclosureGovernor's source-basis check to accept it. :covers describes
  what kind of fact this source can ground."
  [{:id :jpn-houjin-bangou
    :name "法人番号公表サイト (National Tax Agency Corporate Number Publication Site)"
    :jurisdiction :jpn :class :official-registry
    :covers #{:company-registry} :access :public-api
    :url "https://www.houjin-bangou.nta.go.jp"}
   {:id :gbr-companies-house
    :name "Companies House (officers / persons with significant control)"
    :jurisdiction :gbr :class :official-registry
    :covers #{:company-registry :officers-psc} :access :public-api
    :url "https://find-and-update.company-information.service.gov.uk"
    ;; ADR-2607110400 addendum 5: the FIRST entry with a REAL live client
    ;; (dossier.companies-house + dossier.live-store/LiveGbrStore), not
    ;; just a citation target (:global-gleif-lei below is the second).
    ;; Scoped honestly to company-by-name + officials-of a KNOWN company
    ;; id -- a global official-by-name live lookup is NOT built (see
    ;; dossier.companies-house's docstring). :live-capable? is a static
    ;; fact about what code exists, not a runtime check of whether
    ;; COMPANIES_HOUSE_API_KEY is actually set right now -- see
    ;; `dossier.companies-house/configured?` for that.
    :live-capable? true}
   {:id :deu-unternehmensregister
    :name "Unternehmensregister (Federal Company Register)"
    :jurisdiction :deu :class :official-registry
    :covers #{:company-registry} :access :public-website
    :url "https://www.unternehmensregister.de"}
   {:id :est-e-business-register
    :name "e-Business Register (e-äriregister)"
    :jurisdiction :est :class :official-registry
    :covers #{:company-registry :officers-psc} :access :public-api
    :url "https://ariregister.rik.ee"}
   {:id :usa-sec-edgar
    :name "SEC EDGAR (public company filings)"
    :jurisdiction :usa :class :regulatory-filing
    :covers #{:company-registry :officers-psc} :access :public-api
    :url "https://www.sec.gov/edgar"
    ;; 2026-07-15: the THIRD entry with a REAL live client
    ;; (dossier.sec-edgar + dossier.live-store/LiveSecEdgarStore), scoped
    ;; honestly to the `submissions` (registry/disclosure) endpoint ONLY --
    ;; deliberately NOT the XBRL `companyfacts` (financial-facts) endpoint,
    ;; which is cloud-murakumo-market-intel's concern (see
    ;; dossier.sec-edgar's docstring for the exact boundary; this same
    ;; catalog entry was scoped out of a live-client pass earlier,
    ;; ADR-2607150100, specifically to avoid that mix-up while that repo
    ;; stood up its own EDGAR connector). Company-by-name is NOT live here
    ;; -- SEC EDGAR's submissions API has no entity-name search endpoint at
    ;; all, only a known usa-<cik> id lookup (see
    ;; dossier.sec-edgar/->company + dossier.live-store/LiveSecEdgarStore).
    :live-capable? true}
   {:id :eu-sanctions-list
    :name "EU Consolidated Financial Sanctions List"
    :jurisdiction :eu :class :government-sanctions-list
    :covers #{:sanctions-pep} :access :public-website
    :url "https://webgate.ec.europa.eu/fsd"}
   ;; ---- R1 expansion (2026-07-12): eight more primary sources, same
   ;; discipline (real, public, official). Existing :class set only —
   ;; no new source classes are introduced.
   {:id :fra-rne-inpi
    :name "Registre national des entreprises (INPI)"
    :jurisdiction :fra :class :official-registry
    :covers #{:company-registry} :access :public-api
    :url "https://data.inpi.fr"}
   {:id :chn-gsxt
    :name "国家企业信用信息公示系统 (National Enterprise Credit Information Publicity System)"
    :jurisdiction :chn :class :official-registry
    :covers #{:company-registry} :access :public-website
    :url "https://www.gsxt.gov.cn"}
   {:id :ind-mca
    :name "Ministry of Corporate Affairs — MCA company master data"
    :jurisdiction :ind :class :official-registry
    :covers #{:company-registry :officers-psc} :access :public-website
    :url "https://www.mca.gov.in"}
   {:id :kor-dart
    :name "DART 전자공시시스템 (FSS electronic disclosure system)"
    :jurisdiction :kor :class :regulatory-filing
    :covers #{:company-registry :officers-psc} :access :public-api
    :url "https://dart.fss.or.kr"}
   {:id :aus-abn-lookup
    :name "ABN Lookup (Australian Business Register)"
    :jurisdiction :aus :class :official-registry
    :covers #{:company-registry} :access :public-api
    :url "https://abr.business.gov.au"}
   {:id :can-corporations-canada
    :name "Corporations Canada federal corporation database"
    :jurisdiction :can :class :official-registry
    :covers #{:company-registry :officers-psc} :access :public-website
    :url "https://ised-isde.canada.ca/site/corporations-canada/en"}
   {:id :bra-cnpj
    :name "Receita Federal — Consulta CNPJ (cadastro nacional, open data)"
    :jurisdiction :bra :class :official-registry
    :covers #{:company-registry} :access :public-api
    :url "https://www.gov.br/receitafederal/"}
   {:id :usa-ofac-sdn
    :name "OFAC Specially Designated Nationals (SDN) List"
    :jurisdiction :usa :class :government-sanctions-list
    :covers #{:sanctions-pep} :access :public-website
    :url "https://sanctionslist.ofac.treas.gov"}
   ;; ---- R2 expansion (2026-07-13): five more primary sources, same
   ;; discipline. Catalog 14 -> 19: one slot deliberately left under the
   ;; facts-test <= 20 curation guard — the next add must argue its case,
   ;; not slide in.
   {:id :idn-ahu
    :name "Direktorat Jenderal AHU — company registry search"
    :jurisdiction :idn :class :official-registry
    :covers #{:company-registry} :access :public-website
    :url "https://ahu.go.id"}
   {:id :tur-ticaret-sicili-gazetesi
    :name "Türkiye Ticaret Sicili Gazetesi (Trade Registry Gazette)"
    :jurisdiction :tur :class :official-registry
    :covers #{:company-registry :officers-psc} :access :public-website
    :url "https://www.ticaretsicil.gov.tr"}
   {:id :sau-commercial-register
    :name "Saudi Ministry of Commerce — commercial register search"
    :jurisdiction :sau :class :official-registry
    :covers #{:company-registry} :access :public-website
    :url "https://mc.gov.sa"}
   {:id :ita-registro-imprese
    :name "Registro Imprese (Italian Business Register, InfoCamere)"
    :jurisdiction :ita :class :official-registry
    :covers #{:company-registry :officers-psc} :access :public-website
    :url "https://www.registroimprese.it"}
   {:id :nld-kvk-handelsregister
    :name "KVK Handelsregister (Netherlands Chamber of Commerce)"
    :jurisdiction :nld :class :official-registry
    :covers #{:company-registry :officers-psc} :access :public-api
    :url "https://www.kvk.nl"}
   ;; ---- Final R0 slot (2026-07-13): the <= 20 curation guard is now
   ;; FULL. This slot's case: the UN Security Council Consolidated List
   ;; is the canonical global sanctions basis -- the EU consolidated
   ;; list and OFAC SDN entries above are regional/national
   ;; implementations layered on top of it. :jurisdiction :un attaches
   ;; to no single country (honest -- it is supranational), so it
   ;; strengthens the source-basis catalog itself rather than any
   ;; per-country coverage count. Growing past 20 requires renegotiating
   ;; the guard in facts-test with an argued case, not an edit.
   {:id :un-sc-consolidated-list
    :name "UN Security Council Consolidated List"
    :jurisdiction :un :class :government-sanctions-list
    :covers #{:sanctions-pep} :access :public-website
    :url "https://main.un.org/securitycouncil/en/content/un-sc-consolidated-list"}
   ;; ---- 21st entry (2026-07-14): the <= 20 curation guard above was
   ;; deliberately full -- this entry's case, argued rather than slid in
   ;; (facts-test's guard is renegotiated to <= 21 alongside this add, with
   ;; the same comment left there): GLEIF is the ISO 17442 LEI issuing
   ;; body itself, a supranational registry of 2.7M+ legal entities
   ;; worldwide that needs no API key and has a REAL live client
   ;; (`dossier.gleif` + `dossier.live-store/LiveLeiStore`, verified against
   ;; the production API 2026-07-14, not just a citation target) — the
   ;; SECOND entry with real live code after :gbr-companies-house, and the
   ;; broadest-coverage single source in this catalog by a wide margin.
   ;; :jurisdiction :un for the same reason :un-sc-consolidated-list uses
   ;; it: an LEI attaches to no single country (honest -- GLEIF itself is a
   ;; Swiss-based supranational foundation, not a national registry), so
   ;; this strengthens the catalog's global reach rather than any single
   ;; country's coverage count.
   {:id :global-gleif-lei
    :name "GLEIF LEI Registry (Legal Entity Identifier, ISO 17442)"
    :jurisdiction :un :class :official-registry
    :covers #{:company-registry} :access :public-api
    :url "https://www.gleif.org"
    :live-capable? true}])

(def allowed-source-classes
  "The set of `:source :class` values the DisclosureGovernor's source-basis
  check will accept anywhere. Deliberately a closed set — an LLM citing a
  class not in `catalog` (e.g. :inference, :pattern-match, :social-media)
  must be rejected, not silently accepted because it looks like a keyword."
  (into #{} (map :class catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全世界' in prose, ~6 sources in fact). Mirrors
  `cloud-itonami-M6910`'s `formation.facts/coverage`. `:live-capable-
  jurisdictions` is a STATIC fact about which sources have real client code
  (`dossier.companies-house`) — it does not mean a key is currently
  configured; see `dossier.companies-house/configured?` for that runtime
  check."
  []
  {:jurisdictions (into (sorted-set) (map :jurisdiction catalog))
   :source-count (count catalog)
   :covers (into (sorted-set) (mapcat :covers catalog))
   :live-capable-jurisdictions (into (sorted-set) (map :jurisdiction (filter :live-capable? catalog)))
   :note (str "R0 scope: " (count catalog) " public primary sources. Extend only by "
              "appending a real, citable catalog entry.")})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn jurisdiction-sources [jurisdiction]
  (filterv #(= jurisdiction (:jurisdiction %)) catalog))
