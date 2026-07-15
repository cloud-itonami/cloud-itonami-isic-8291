# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-8291`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-8291
cd cloud-itonami-isic-8291
clojure -M:dev:test
clojure -M:dev:run
```

The default demo uses entirely fictitious data. Production company/official
records must stay outside the repository and be injected through a store
adapter, and every fact must carry a real, verifiable source citation.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one organization owns infrastructure and data |
| Managed tenant | an operator hosts for a customer |
| Certified operator | itonami.cloud has reviewed security and process controls |

## 3. Production Checklist

- replace demo data with real, source-cited registry/sanctions feeds (extend
  `dossier.facts/catalog` honestly — never fabricate a source entry)
- for global LEI coverage: nothing to configure. `dossier.live-store/
  live-store` (0-arg or 1-arg) chains a live GLEIF LEI Registry fallback
  (`dossier.gleif`) by default — GLEIF's public `/lei-records` API needs no
  API key at all, so this live source is on the moment you build the actor
  with `(live/live-store)` instead of a bare `MemStore`/`DatomicStore`.
  Verified against the real production API 2026-07-14 (`clojure -M:dev -e
  "(require '[dossier.gleif :as g]) (println (g/->company (g/lei-record
  (g/live-http-fn) \"HWUPKR0MPOU8FGXBT394\")))"` — Apple Inc.'s real LEI —
  returned a correctly-mapped company). Coverage is broad (2.7M+ entities
  worldwide) but shallow: name/address/status/legal-form only, no
  officers/directors/UBOs — `officials-of` is never GLEIF-sourced.
- for GBR: get a free Companies House API key
  (https://developer.company-information.service.gov.uk/) and set
  `COMPANIES_HOUSE_API_KEY`. `dossier.live-store/live-store` picks this up
  automatically once the env var is set (no code change needed) and chains
  it alongside the GLEIF fallback above — local/seeded data still always
  wins over either. Without the env var, the Companies House fallback is
  simply absent (GLEIF still works); this pass shipped with a fully
  offline-tested CH integration (a fake fetch-fn), not a verified live CH
  call (no key was available at build time) — GLEIF, unlike CH, WAS
  verified live at build time since it needs no key.
- for USA registry/disclosure data (entity name, SIC code, addresses,
  state of incorporation, former names, tickers, exchanges — NOT financial
  facts like revenue/assets, which is a different actor's concern
  entirely, see below): no key/application needed, only an honest
  identifying string. Set `SEC_EDGAR_USER_AGENT` to a real `name email`
  value (SEC's fair-use policy requires every request identify a caller —
  https://www.sec.gov/os/webmaster-faq#developers — an unset/generic
  User-Agent risks throttling). `dossier.live-store/live-store` picks this
  up automatically once the env var is set (no code change needed) and
  chains it alongside the Companies House/GLEIF fallbacks above —
  local/seeded data still always wins. Without the env var, the SEC EDGAR
  fallback is simply absent (the other two still work). Live lookups are
  by a known `usa-<cik>` id ONLY (`store/company`) — SEC EDGAR's
  `submissions` API has no entity-name search endpoint at all, so
  `store/company-by-name` never resolves a SEC-EDGAR-only name. Verified
  against the real production API 2026-07-15 (`SEC_EDGAR_USER_AGENT="dossier
  jun784@gmail.com" clojure -M:dev -e "(require '[dossier.sec-edgar :as
  sec]) (println (sec/->company (sec/submissions (sec/live-http-fn
  (sec/env-user-agent)) 320193)))"` — Apple Inc.'s real CIK 320193 —
  returned a correctly-mapped company; Microsoft's CIK 789019 verified the
  same way). **Do not point this at the XBRL `companyfacts` endpoint** —
  that financial-facts API is `cloud-murakumo-market-intel`'s connector,
  a deliberately separate concern from this actor's registry/disclosure
  scope (see `dossier.sec-edgar`'s ns docstring for the exact boundary).
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- define customer contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test`
- run `clojure -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- document the correction/dispute-handling SLA
- get written legal review for the jurisdictions you serve (GDPR/APPI/CCPA
  profiling and credit-reporting rules vary by jurisdiction)

## 4. Sales Motion

Start with a narrow offer:

1. onboard one real, citable data source (e.g. a national company registry)
2. prove governed, tier-scoped disclosure end to end
3. run one relationship-draft workflow in assisted mode (human-approved)
4. export the audit ledger for review
5. convert to a metered or subscription contract

Avoid selling broad "全世界の企業/人物データベース" before the source catalog
actually covers the jurisdictions a customer needs — report coverage
honestly (`dossier.facts/coverage`), never oversell.

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- written data-flow diagram (source → governor → disclosure)
- backup/restore evidence
- incident contact and response window
- proof that production writes/disclosures go through DisclosureGovernor
- proof that real company/individual data is not stored in Git
- proof that a correction/dispute channel exists and is human-reviewed
- customer-facing support and licensing terms

## 6. Operator Responsibilities

Operators are responsible for:

- lawful basis for each data source and jurisdiction served
- local privacy/credit-reporting/profiling-law review (GDPR, APPI, CCPA,
  FCRA-equivalent regimes)
- secure infrastructure and tenant isolation
- honest source-catalog maintenance
- human review workflow for high-stakes and correction-request operations
- data-retention policy
- security updates

The OSS project provides software and an operating blueprint. It does not
make an operator compliant by itself, and it does not license or endorse
scope beyond professional/official-capacity, source-cited facts.
