(ns dossier.gleif
  "Real GLEIF (Global Legal Entity Identifier Foundation) LEI registry API
  client — the SECOND live-data seam this actor's R0 source catalog has
  (`dossier.facts`, `:global-gleif-lei` entry), same shape as
  `dossier.companies-house`: JVM-only plain `.clj` (`org.httpkit.client` +
  `jsonista`), `fetch-fn` (`{:path :query} -> parsed-json|nil`) injected so
  production uses `live-http-fn` and tests inject a canned fake — no real
  network access in `clojure -M:dev:test` (`test/dossier/gleif_test.clj`).

  Unlike Companies House, GLEIF's public `/lei-records` API needs NO API
  key at all — LEI records are open reference data by design (ISO 17442;
  the entity itself pays its LOU to register, not the reader), so
  `configured?` is always true and `dossier.live-store` never needs an env
  var for this source. Coverage: GLEIF's own count is 2.7M+ legal entities
  worldwide, in any jurisdiction that has ever had an LEI issued — far
  broader in BREADTH than any single national registry this actor cites,
  but narrower in DEPTH: an LEI record carries entity name/address/legal-
  form/status, NOT officers/directors/UBOs. `officials-of` is never live-
  sourced from GLEIF — there is no such endpoint to source it from.

  R0 scope (honest, not exhaustive):
    - `company` by a known `lei-<LEI>` id (`GET /lei-records/{lei}`).
    - `company-by-name` — GLEIF's own `filter[entity.legalName]` search is
      fuzzy/full-text, NOT exact (confirmed empirically 2026-07-14: querying
      \"Barclays Bank\" also returns \"BARCLAYS BANK SA\", a different legal
      entity), so `find-lei-by-name` re-applies the same case-insensitive
      EXACT-match-or-nothing discipline `dossier.companies-house/find-
      company-by-name` uses — never a fuzzy guess promoted to an answer.
    - No officer/PSC/ownership data of any kind.

  `:jurisdiction` is derived from `entity.legalAddress.country` (ISO 3166-1
  alpha-2, e.g. \"US\"/\"GB\") via a small static alpha-2 -> alpha-3 map
  below — the kotoba-lang/iso3166 registry (checked before writing this)
  only carries alpha-3 codes with no alpha-2 index, so there is no shared
  conversion utility to reuse yet. The map intentionally covers only the
  countries most likely to appear among GLEIF registrants relevant to this
  actor's existing R0 jurisdictions plus other major economies — a country
  not in the map degrades `:jurisdiction` to nil, never a fabricated guess."
  (:require [clojure.string :as str]
            [org.httpkit.client :as http]
            [jsonista.core :as j]))

(def base-url "https://api.gleif.org/api/v1")

(defn configured?
  "GLEIF's public API needs no key, so this is always true — kept only for
  interface parity with `dossier.companies-house/configured?` so
  `dossier.live-store` can reason about every live source uniformly. A
  real transport/outage failure still degrades to nil via `live-http-fn`,
  never an exception; this flag is about auth requirements, not
  reachability."
  [] true)

(defn live-http-fn
  "The real http-kit-backed fetch fn: `{:path :query}` -> parsed JSON map,
  or nil on any transport/not-found/rate-limit problem (never throws — a
  live-data outage degrades to 'no additional data', it does not crash the
  actor). Injectable, like `dossier.companies-house/live-http-fn` — tests
  supply a fake map->map fn instead of this one. No auth header: GLEIF's
  API is unauthenticated."
  []
  (fn [{:keys [path query]}]
    (try
      (let [{:keys [status body error]}
            @(http/get (str base-url path)
                       {:query-params query
                        :headers {"Accept" "application/json"}})]
        (when (and (not error) (= 200 status) body)
          (j/read-value body j/keyword-keys-object-mapper)))
      (catch Exception _ nil))))

;; ───────────────────────── raw endpoint calls ─────────────────────────
;; Each takes `fetch-fn` ({:path :query} -> parsed-json|nil) so tests inject
;; a canned fake and production injects `live-http-fn` — the endpoint
;; functions themselves never know which.

(defn search-by-name
  "GET /lei-records?filter[entity.legalName]={name}&page[size]={n} —
  GLEIF's own filter is fuzzy/full-text (see ns docstring), so this
  returns the RAW `:data` vector of JSON:API lei-record resources exactly
  as GLEIF ranked them; `find-lei-by-name` narrows that to an exact match.
  Returns nil if `fetch-fn` returned nothing (e.g. a transport failure)."
  ([fetch-fn name] (search-by-name fetch-fn name 10))
  ([fetch-fn name page-size]
   (:data (fetch-fn {:path "/lei-records"
                      :query {"filter[entity.legalName]" name
                              "page[size]" page-size}}))))

(defn lei-record
  "GET /lei-records/{lei} — a single JSON:API lei-record resource by its
  20-character LEI code, or nil (unknown/malformed LEI -> 404, or any
  transport failure — both degrade the same way, never an exception)."
  [fetch-fn lei]
  (:data (fetch-fn {:path (str "/lei-records/" lei)})))

(defn find-lei-by-name
  "Best-effort EXACT match, case-insensitive (GLEIF stores legal names in
  varying case across records — confirmed empirically querying the same
  company name in different cases returns the same record): the first
  search result whose `entity.legalName.name` equals `name` letter-for-
  letter modulo case. Never a fuzzy guess promoted to an answer, same
  discipline as `dossier.companies-house/find-company-by-name`."
  [fetch-fn name]
  (let [needle (str/lower-case name)]
    (some #(when (= needle (str/lower-case (get-in % [:attributes :entity :legalName :name] "")))
             %)
          (search-by-name fetch-fn name))))

;; ───────────────────── alpha-2 -> alpha-3 jurisdiction map ─────────────
;; Small, honestly-partial (see ns docstring): covers this actor's existing
;; R0 jurisdictions (dossier.facts/catalog) plus other major economies
;; likely to show up among GLEIF registrants. A country not listed here
;; degrades `:jurisdiction` to nil rather than guessing.

(def ^:private alpha2->alpha3
  {"JP" :jpn "GB" :gbr "DE" :deu "EE" :est "US" :usa "FR" :fra "CN" :chn
   "IN" :ind "KR" :kor "AU" :aus "CA" :can "BR" :bra "ID" :idn "TR" :tur
   "SA" :sau "IT" :ita "NL" :nld
   ;; other major economies not yet in dossier.facts/catalog
   "CH" :che "SG" :sgp "HK" :hkg "MX" :mex "ES" :esp "SE" :swe "NO" :nor
   "DK" :dnk "FI" :fin "PL" :pol "RU" :rus "ZA" :zaf "AE" :are "IE" :irl
   "LU" :lux "BE" :bel "AT" :aut "PT" :prt "GR" :grc "NZ" :nzl "IL" :isr
   "TH" :tha "MY" :mys "PH" :phl "VN" :vnm "PK" :pak "EG" :egy "NG" :nga
   "KE" :ken "AR" :arg "CL" :chl "CO" :col "PE" :per})

(defn- jurisdiction-of
  "ISO 3166-1 alpha-2 country string (as GLEIF returns it, e.g. \"US\") ->
  this repo's alpha-3 lowercase keyword (e.g. :usa), or nil when the code
  is absent/unmapped."
  [alpha2]
  (get alpha2->alpha3 alpha2))

;; ───────────────────────── mappers -> 8291 native shapes ─────────────────

(defn ->company
  "GLEIF lei-record -> `dossier.store` company shape. `:id` is namespaced
  `lei-<LEI>` (parallel to `dossier.companies-house`'s `gbr-<company_
  number>`) so `dossier.live-store` can tell a GLEIF-sourced id apart from
  a local/demo one or a Companies House one at a glance. `:lei` is a new
  first-class attribute (see `dossier.store` schema addition) carrying the
  LEI code itself, distinct from `:registration-no` — GLEIF's own
  `registeredAs` field, which is the underlying NATIONAL company-
  registration number the entity's LEI Issuer validated it against (e.g.
  a UK company number), when one was supplied."
  [record]
  (when record
    (let [lei (get-in record [:attributes :lei])
          entity (get-in record [:attributes :entity])
          country (get-in entity [:legalAddress :country])]
      {:id (str "lei-" lei)
       :legal-name (get-in entity [:legalName :name])
       :jurisdiction (jurisdiction-of country)
       :registration-no (:registeredAs entity)
       :status (some-> (:status entity) str/lower-case keyword)
       :lei lei
       :source {:class :official-registry
                :ref (str base-url "/lei-records/" lei)}
       :flags {}})))
