(ns dossier.companies-house
  "Real UK Companies House public data API client (ADR-2607110400 addendum
  5) — the first live-data seam for this actor's R0 source catalog
  (`dossier.facts`, `:gbr-companies-house` entry). JVM-only plain `.clj`
  (`org.httpkit.client` + `jsonista` have no cljs equivalent here) — the
  same host-fn-injection shape `langchain.jvm/jvm-http-fn` already uses in
  this workspace, so this whole actor fleet's JVM-via-`clojure -M:dev:test`
  convention stays intact and tests never need a real API key or network
  access (the fetch fn is always injected; `test/dossier/companies_house_
  test.clj` injects a canned fake). Plain `.clj`, not `.cljc`, for the same
  reason `talent.facts.clj` (gftd-talent-actor) is plain `.clj`: a
  filesystem/network integration concern is a dev/ops seam, not part of the
  portable actor core.

  Auth: HTTP Basic, the API key as username, empty password (Companies
  House's own convention). The key is read from the `COMPANIES_HOUSE_API_KEY`
  environment variable ONLY — never hardcoded, never committed, matching
  this workspace's secrets discipline (`manifest/repos.edn` credentials map).
  When absent, `configured?` reports false and `dossier.live-store` falls
  back to local-only data rather than throwing — the same honest-degradation
  discipline `talent.facts`'s m365-archive fallback uses elsewhere in this
  workspace.

  R0 scope (honest, not exhaustive — see ADR addendum 5): live lookups cover
  `company-by-name` (exact-title match against `/search/companies`) and
  `officials-of` a KNOWN company (`/company/{number}/officers`). A global
  'find any officer anywhere by name' live lookup is NOT built in this pass
  — Companies House's `/search/officers` returns a person without their
  current company context, requiring a second `/officers/{id}/appointments`
  hop per candidate to resolve; deferred rather than half-built. This means
  `:disclosure/screen-name` (the op the 5 direct-pattern pilot consumers
  actually call) does not yet benefit from live GBR data — a real, stated
  limitation, not a silent gap."
  (:require [org.httpkit.client :as http]
            [jsonista.core :as j]))

(def base-url "https://api.company-information.service.gov.uk")

(defn env-api-key
  "The API key from the environment, or nil."
  []
  (System/getenv "COMPANIES_HOUSE_API_KEY"))

(defn configured?
  "True when `api-key` looks usable. Callers should still handle a nil
  fetch result gracefully (a stale/revoked key, rate limiting, or a
  transport error all degrade to nil, never an exception)."
  [api-key]
  (boolean (seq api-key)))

(defn live-http-fn
  "The real http-kit-backed fetch fn: `{:path :query}` -> parsed JSON map,
  or nil on any transport/auth/not-found/rate-limit problem (never
  throws — a live-data outage degrades to 'no additional data', it does
  not crash the actor). Injectable, like `langchain.jvm/jvm-http-fn` —
  tests supply a fake map->map fn instead of this one."
  [api-key]
  (fn [{:keys [path query]}]
    (try
      (let [{:keys [status body error]}
            @(http/get (str base-url path)
                       {:basic-auth [api-key ""]
                        :query-params query
                        :headers {"Accept" "application/json"}})]
        (when (and (not error) (= 200 status) body)
          (j/read-value body j/keyword-keys-object-mapper)))
      (catch Exception _ nil))))

;; ───────────────────────── raw endpoint calls ─────────────────────────
;; Each takes `fetch-fn` ({:path :query} -> parsed-json|nil) so tests inject
;; a canned fake and production injects `live-http-fn` — the endpoint
;; functions themselves never know which.

(defn search-companies
  "GET /search/companies?q={name} — ranked, fuzzy results. Returns the raw
  `:items` vector (each `{:company_number :title :company_status ...}`), or
  nil if `fetch-fn` returned nothing."
  [fetch-fn name]
  (:items (fetch-fn {:path "/search/companies" :query {:q name}})))

(defn company-profile
  "GET /company/{company_number} — full registry profile."
  [fetch-fn company-number]
  (fetch-fn {:path (str "/company/" company-number)}))

(defn company-officers
  "GET /company/{company_number}/officers — current + former officers."
  [fetch-fn company-number]
  (:items (fetch-fn {:path (str "/company/" company-number "/officers")})))

(defn company-psc
  "GET /company/{company_number}/persons-with-significant-control — UBO
  disclosures. Not yet mapped into `dossier.store` entities (ADR addendum
  5 scope): exposed here for a future ownership-chain live extension."
  [fetch-fn company-number]
  (:items (fetch-fn {:path (str "/company/" company-number "/persons-with-significant-control")})))

(defn find-company-by-name
  "Best-effort EXACT match: the first search result whose `:title` equals
  `name` verbatim. Companies House's search is fuzzy/ranked; R0 wants the
  same 'exact match or nothing' discipline `dossier.store/company-by-name`/
  `official-by-name` already use — never a fuzzy guess."
  [fetch-fn name]
  (some #(when (= name (:title %)) %) (search-companies fetch-fn name)))

;; ───────────────────────── mappers -> 8291 native shapes ─────────────────

(defn ->company
  "Companies House company profile -> `dossier.store` company shape. `:id`
  is namespaced `gbr-<company_number>` so `dossier.live-store` can tell a
  live-sourced id apart from a local/demo one at a glance."
  [profile]
  (when profile
    {:id (str "gbr-" (:company_number profile))
     :legal-name (:company_name profile)
     :jurisdiction :gbr
     :registration-no (:company_number profile)
     :status (some-> (:company_status profile) keyword)
     :source {:class :official-registry
              :ref (str base-url "/company/" (:company_number profile))}
     :flags {}}))

(defn ->official
  "One Companies House officer entry -> `dossier.store` official shape.
  `:id` is derived from the officer's `appointments` link when present
  (a stable Companies House identifier); falls back to a hash of the name
  + company (the officer list endpoint alone does not always expose the
  link) — good enough to be a stable map key within one process, NOT
  claimed as a durable cross-session identifier."
  [org-id company-number officer]
  (when officer
    {:id (or (get-in officer [:links :officer :appointments])
             (str "gbr-officer-" company-number "-" (hash (:name officer))))
     :name (:name officer)
     :title (:officer_role officer)
     :org org-id
     :capacity (if (= "director" (:officer_role officer)) :director :officer)
     :source {:class :official-registry
              :ref (str base-url "/company/" company-number "/officers")}}))
