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
    ;; ADR-2607110400 addendum 5: the only entry with a REAL live client
    ;; (dossier.companies-house + dossier.live-store), not just a citation
    ;; target. Scoped honestly to company-by-name + officials-of a KNOWN
    ;; company id -- a global official-by-name live lookup is NOT built
    ;; (see dossier.companies-house's docstring). :live-capable? is a
    ;; static fact about what code exists, not a runtime check of whether
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
    :url "https://www.sec.gov/edgar"}
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
    :url "https://sanctionslist.ofac.treas.gov"}])

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
   :note "R0 scope: 6 public primary sources. Extend only by appending a real, citable catalog entry."})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn jurisdiction-sources [jurisdiction]
  (filterv #(= jurisdiction (:jurisdiction %)) catalog))
