(ns dossier.houjin-bangou
  "Real Japanese National Tax Agency 法人番号 (Corporate Number) Web-API
  client (ADR-2607182200) -- the second live-data seam for this actor's
  R0 source catalog (`dossier.facts`, `:jpn-houjin-bangou` entry, already
  cataloged with `:access :public-api` since ADR-2607110400 but never
  wired to a real client until now). JVM-only plain `.clj`
  (`org.httpkit.client` + `clojure.xml`), same rationale as
  `dossier.companies-house`: a filesystem/network integration concern is
  a dev/ops seam, not part of the portable actor core.

  ## Why XML, not JSON

  The API is XML/CSV-native (no server-side JSON mode -- third-party
  wrapper libraries that advertise 'JSON support' are doing a client-side
  XML->JSON conversion, not calling a different server endpoint). This
  client requests `type=12` (XML, full-width Unicode) and parses with
  `clojure.xml` (JVM standard library, zero new dependency -- matching
  this repo's dependency-minimalism, same reasoning `companies_house.clj`
  gives for reusing `org.httpkit.client`/`jsonista` rather than adding a
  third HTTP/JSON stack).

  ## Auth: Application ID, NOT a same-day API key

  Unlike Companies House (`COMPANIES_HOUSE_API_KEY`, self-serve, instant)
  and GLEIF (keyless), this API requires a free 'アプリケーションID'
  (Application ID) obtained through a manual National Tax Agency
  registration process (email-based, ~2-4 weeks turnaround, requires the
  applicant's real name/organization) -- see
  https://www.houjin-bangou.nta.go.jp/webapi/. This is NOT something an
  agent can self-serve or apply for on an operator's behalf (it registers
  the operator's own identity with a government agency). Read from
  `HOUJIN_BANGOU_APPLICATION_ID` ONLY -- never hardcoded, never committed,
  same secrets discipline as `COMPANIES_HOUSE_API_KEY`. When unset,
  `configured?` reports false and `dossier.live-store` falls back to
  local-only data, identical honest-degradation discipline.

  ## Verification status (honest, per ADR-2607182200)

  This client's request/response shapes are built from the REAL, public
  API specification (cross-checked against the open-source TypeScript
  wrapper `totechite/houjinbangou-api-wrapper`'s `src/types.ts` --
  request query keys and the full `corporation` response schema, not
  guessed) and covered by offline tests with realistic fixture XML
  matching that schema. It has NOT been exercised against the live API
  with a real Application ID in the environment this file was built in
  (none was available -- see the ADR). An operator who completes the
  registration and sets `HOUJIN_BANGOU_APPLICATION_ID` gets a genuinely
  wired client, but its real-call behavior remains unverified until they
  do -- same honest gap `crm.llm-realmodel` documented before its own
  live verification landed.

  R0 scope: `search-by-name` (前方一致 prefix match, `mode=1`) and
  `by-corporate-number` (exact number, up to 10 batched). A
  National-Tax-Agency-wide fuzzy/full-text company search is NOT built
  (the API's `mode=2` partial-match exists but R0 sticks to the same
  'exact match or nothing' discipline `dossier.store/company-by-name`
  already uses elsewhere in this actor, mirroring Companies House's own
  R0 restraint)."
  (:require [org.httpkit.client :as http]
            [clojure.xml :as xml]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)))

(def base-url "https://api.houjin-bangou.nta.go.jp")
(def api-version 4)

(defn env-api-key
  "The Application ID from the environment, or nil."
  []
  (System/getenv "HOUJIN_BANGOU_APPLICATION_ID"))

(defn configured?
  "True when `api-key` looks usable. Callers should still handle a nil
  fetch result gracefully (a stale/revoked id, rate limiting, or a
  transport error all degrade to nil, never an exception)."
  [api-key]
  (boolean (seq api-key)))

;; ───────────────────────── XML -> map ─────────────────────────

(defn parse-xml [^String body]
  (xml/parse (ByteArrayInputStream. (.getBytes body "UTF-8"))))

(defn- text-of
  "An element's text content, or \"\" for a self-closing/empty element --
  NEVER nil, matching the real API's own convention that an absent
  optional field (e.g. `closeDate` on an active corporation) is an empty
  tag, not a missing one."
  [el]
  (->> (:content el) (filter string?) (str/join)))

(defn element->flat-map
  "One <corporation>...</corporation> element -> {:corporateNumber \"...\"
  :name \"...\" ...} -- every direct child tag -> its text content,
  matching `corporation`'s field set in the real API schema (verified
  against `totechite/houjinbangou-api-wrapper`'s `src/types.ts`, not
  guessed). Missing/self-closing tags simply produce a blank string, same
  as the real API's own optional-field behavior."
  [el]
  (into {}
        (map (fn [child] [(:tag child) (text-of child)]))
        (:content el)))

(defn parse-corporations
  "Root <corporations> element -> vector of flat corporation maps (zero,
  one, or many <corporation> children -- the real API omits the wrapper
  entirely on a zero-result response, which `(:content root)` handles as
  an empty/nil seq without special-casing)."
  [root]
  (into []
        (comp (filter #(= :corporation (:tag %)))
              (map element->flat-map))
        (:content root)))

;; ───────────────────────── live http ─────────────────────────

(defn live-http-fn
  "The real http-kit-backed fetch fn: `{:path :query}` -> parsed
  corporations vector, or nil on any transport/auth/not-found/rate-limit
  problem (never throws -- a live-data outage degrades to 'no additional
  data', it does not crash the actor). Injectable, like
  `dossier.companies-house/live-http-fn` -- tests supply a fake
  path->xml-string fn instead of this one."
  [api-key]
  (fn [{:keys [path query]}]
    (try
      (let [{:keys [status body error]}
            @(http/get (str base-url path)
                       {:query-params (assoc query "id" api-key "type" "12")})]
        (when (and (not error) (= 200 status) body)
          (parse-corporations (parse-xml body))))
      (catch Exception _ nil))))

;; ───────────────────────── raw endpoint calls ─────────────────────────
;; Each takes `fetch-fn` ({:path :query} -> corporations-vector|nil) so
;; tests inject a canned fake and production injects `live-http-fn` --
;; the endpoint functions themselves never know which.

(defn search-by-name
  "GET /{version}/name?name={name}&mode=1 -- 前方一致 (prefix match).
  Returns the raw corporations vector, or nil if `fetch-fn` returned
  nothing."
  [fetch-fn name]
  (fetch-fn {:path (str "/" api-version "/name") :query {"name" name "mode" "1"}}))

(defn by-corporate-number
  "GET /{version}/num?number={number} -- up to 10 comma-separated 13-digit
  corporate numbers per the real API's own batching limit."
  [fetch-fn numbers]
  (fetch-fn {:path (str "/" api-version "/num")
             :query {"number" (str/join "," (if (coll? numbers) numbers [numbers]))}}))

(defn find-company-by-name
  "Best-effort EXACT match: the first search result whose `:name` equals
  `name` verbatim. The API's own `mode=1` is already prefix-anchored, but
  R0 wants the same 'exact match or nothing' discipline
  `dossier.store/company-by-name`/`dossier.companies-house/find-company-
  by-name` already use -- never a fuzzy guess."
  [fetch-fn name]
  (some #(when (= name (get % :name)) %) (search-by-name fetch-fn name)))

;; ───────────────────────── mapper -> 8291 native shape ─────────────────

(defn ->company
  "One houjin-bangou corporation map -> `dossier.store` company shape.
  `:id` is namespaced `jpn-<corporateNumber>` so `dossier.live-store` can
  tell a live-sourced id apart from a local/demo one at a glance, same
  convention `dossier.companies-house/->company` uses for `gbr-`."
  [corp]
  (when (seq (get corp :corporateNumber))
    (let [close-date (get corp :closeDate)]
      {:id (str "jpn-" (get corp :corporateNumber))
       :legal-name (get corp :name)
       :jurisdiction :jpn
       :registration-no (get corp :corporateNumber)
       :status (if (seq close-date) :dissolved :active)
       :source {:class :official-registry
                :ref (str base-url "/" api-version "/num?number=" (get corp :corporateNumber))}
       :flags {}})))
