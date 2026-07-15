(ns dossier.sec-edgar
  "Real SEC EDGAR `submissions` API client — the THIRD live-data seam this
  actor's R0 source catalog has (`dossier.facts`, `:usa-sec-edgar` entry),
  same shape as `dossier.companies-house`/`dossier.gleif`: JVM-only plain
  `.clj` (`org.httpkit.client` + `jsonista`), `fetch-fn` (`{:path :query}
  -> parsed-json|nil`) injected so production uses `live-http-fn` and tests
  inject a canned fake — no real network access in `clojure -M:dev:test`
  (`test/dossier/sec_edgar_test.clj`).

  Scope, precisely (do not confuse with a different SEC EDGAR API): this
  client speaks ONLY to the `submissions` endpoint
  (`https://data.sec.gov/submissions/CIK{10-digit}.json`), which is
  REGISTRY/DISCLOSURE metadata — entity name, SIC code, addresses, state of
  incorporation, former names, tickers, exchanges. It does NOT speak to the
  XBRL `companyfacts` endpoint (`/api/xbrl/companyfacts/CIK{...}.json`,
  FINANCIAL facts like revenue/assets) — that endpoint is a different
  actor's concern entirely (`cloud-murakumo-market-intel`) and is
  deliberately untouched here, both in code and in any shared fixture data,
  to avoid the exact mix-up its name invites. `:usa-sec-edgar`'s catalog
  entry was scoped OUT of a live-client pass (ADR-2607150100) specifically
  to avoid this confusion while that other repo was standing up its own
  EDGAR connector; this client narrows back in on the registry/disclosure
  slice only, now that the boundary this docstring states (`submissions`,
  never `companyfacts`) removes the ambiguity.

  Auth: none required by SEC to READ this endpoint, but SEC's fair-use
  policy (https://www.sec.gov/os/webmaster-faq#developers) REQUIRES every
  request to carry an identifying `User-Agent` header (`name email` —
  unidentified/generic User-Agents get throttled or blocked). The value is
  read from the `SEC_EDGAR_USER_AGENT` environment variable ONLY — never
  hardcoded, never committed, matching this workspace's secrets discipline
  — and, same discipline as `dossier.companies-house`, this file itself
  never reads the environment: callers (`dossier.live-store`) read the env
  var and inject it. Unlike Companies House's API key, this is not a secret
  credential — but the same `configured?`/gating shape is reused because a
  request sent without one is not reliably honored by SEC's servers, so
  `dossier.live-store` should degrade to local-only rather than send
  identify-less traffic on an operator's behalf.

  R0 scope (honest, not exhaustive): `company` by a known `usa-<cik>` id
  only (`GET /submissions/CIK{cik}.json`). There is no company-BY-NAME
  lookup — SEC EDGAR's `submissions` API has no free-text entity-name
  search endpoint at all (the closest thing, `company_tickers.json`, is a
  bulk static file keyed by ticker, not a search endpoint, and is not
  fetched here); `dossier.live-store`'s SEC EDGAR decorator therefore never
  intercepts `company-by-name`, only direct `usa-<cik>` id lookups — a real,
  stated limitation, not a silent gap. No officer/director/UBO data of any
  kind is exposed by this endpoint, so `officials-of` is never SEC-EDGAR-
  sourced either."
  (:require [org.httpkit.client :as http]
            [jsonista.core :as j]))

(def base-url "https://data.sec.gov")

(defn env-user-agent
  "The `User-Agent` header value from the environment, or nil."
  []
  (System/getenv "SEC_EDGAR_USER_AGENT"))

(defn configured?
  "True when `user-agent` looks usable. Callers should still handle a nil
  fetch result gracefully (a malformed CIK, rate limiting, or a transport
  error all degrade to nil, never an exception)."
  [user-agent]
  (boolean (seq user-agent)))

(defn live-http-fn
  "The real http-kit-backed fetch fn: `{:path :query}` -> parsed JSON map,
  or nil on any transport/not-found/rate-limit problem (never throws — a
  live-data outage degrades to 'no additional data', it does not crash the
  actor). Injectable, like `dossier.companies-house/live-http-fn` — tests
  supply a fake map->map fn instead of this one. `user-agent` is sent
  verbatim as the `User-Agent` header (SEC fair-use requirement — see ns
  docstring); no other auth."
  [user-agent]
  (fn [{:keys [path query]}]
    (try
      (let [{:keys [status body error]}
            @(http/get (str base-url path)
                       {:query-params query
                        :headers {"User-Agent" user-agent "Accept" "application/json"}})]
        (when (and (not error) (= 200 status) body)
          (j/read-value body j/keyword-keys-object-mapper)))
      (catch Exception _ nil))))

;; ───────────────────────── raw endpoint calls ─────────────────────────

(defn- pad-cik
  "Normalizes a CIK given as a bare int/string (e.g. `320193`,
  `\"320193\"`) or an already zero-padded string (e.g. `\"0000320193\"`,
  the form the raw `submissions` JSON itself uses for `:cik`) to the
  10-digit zero-padded string the endpoint PATH requires. Not defensive
  against non-numeric input beyond `Long/parseLong` throwing — a malformed
  CIK is a caller bug, not a live-data-outage case."
  [cik]
  (format "%010d" (Long/parseLong (str cik))))

(defn submissions
  "GET /submissions/CIK{10-digit-zero-padded}.json — full entity submissions
  metadata. Returns the raw parsed JSON map (`:cik :entityType :sic
  :sicDescription :name :tickers :exchanges :stateOfIncorporation
  :addresses :formerNames :filings ...`) exactly as SEC returns it, or nil
  if `fetch-fn` returned nothing; `->company` below maps only the
  registry-identity subset this actor's narrow `dossier.store` schema has
  room for (see its own docstring for what is deliberately NOT mapped)."
  [fetch-fn cik]
  (fetch-fn {:path (str "/submissions/CIK" (pad-cik cik) ".json")}))

;; ───────────────────────── mappers -> 8291 native shapes ─────────────────

(defn ->company
  "SEC EDGAR submissions response -> `dossier.store` company shape. `:id`
  is namespaced `usa-<cik>` (10-digit zero-padded, parallel to
  `dossier.companies-house`'s `gbr-<company_number>` and `dossier.gleif`'s
  `lei-<LEI>`) so `dossier.live-store` can tell a SEC-EDGAR-sourced id
  apart from a local/demo one, a Companies House one, or a GLEIF one at a
  glance. `:registration-no` is the same zero-padded CIK — SEC EDGAR's own
  registry number for the entity, distinct from any national company
  number or LEI a subsidiary/parent might also carry.

  `:status` is NOT derived from any field in this response — the
  `submissions` endpoint, unlike Companies House's `company_status`,
  exposes no explicit active/dissolved/administratively-dissolved flag at
  all (`stateOfIncorporation` is a jurisdiction fact, not a status one).
  This maps `:status` to `:active` unconditionally as a stated, honest
  ASSUMPTION (SEC filers overwhelmingly are ongoing entities; one that
  stops existing typically stops filing rather than being flagged) — never
  claimed as an SEC-sourced fact.

  Several fields SEC actually returns (`sic`/`sicDescription`, `addresses`,
  `formerNames`, `tickers`, `exchanges`, `ein`) are deliberately NOT mapped
  here: `dossier.store`'s company schema is intentionally narrow (see its
  own ns docstring) and has no attribute slots for them yet — an honest
  scope boundary, not a silent drop bug; `submissions` (above) still
  exposes the raw response for any future extension that adds them as
  first-class fields the same way `dossier.gleif` added `:lei`.

  `:source :class` is `:regulatory-filing`, matching `dossier.facts`'s
  `:usa-sec-edgar` catalog entry (Companies House/GLEIF use
  `:official-registry` — SEC EDGAR is filings-based disclosure, a
  different real class already in `dossier.facts/allowed-source-classes`
  via `:kor-dart`)."
  [submission]
  (when submission
    (let [cik (pad-cik (:cik submission))]
      {:id (str "usa-" cik)
       :legal-name (:name submission)
       :jurisdiction :usa
       :registration-no cik
       :status :active
       :source {:class :regulatory-filing
                :ref (str base-url "/submissions/CIK" cik ".json")}
       :flags {}})))
