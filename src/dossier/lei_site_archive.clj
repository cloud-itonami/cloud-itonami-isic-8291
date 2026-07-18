(ns dossier.lei-site-archive
  "Real client for this workspace's OWN `cloud-itonami-lei-<LEI>` archive
  repos (ADR-2607110300 + ADR-2607182500) -- a SECOND, non-authoritative
  web-presence enrichment source at the SAME governed-vs-enrichment tier
  `dossier.commoncrawl` established, not a registry fact source. JVM-only
  plain `.clj`, same `org.httpkit.client` pair the other client modules
  use, keyless (public GitHub raw content, no auth needed).

  ## Same architectural exclusion as dossier.commoncrawl, for the same reason

  `80-data/public/site.journal.edn` in each `cloud-itonami-lei-<LEI>` repo
  is real, but it's OUR OWN periodic 'does this URL respond, what's its
  title/description' check (ADR-2607182500) -- not a registry fact. It
  can confirm 'this official site was reachable on this date', never
  'this is a real registered company' (the LEI/legal-name themselves
  already came from a real registry when these archive repos were first
  created, per ADR-2607110300 -- THAT provenance lives in `blueprint.edn`,
  which this module also reads, but does not re-verify or re-assert as
  its own fact). So, matching `dossier.commoncrawl` exactly: no
  `->company` mapper here, NOT added to `dossier.facts`'s catalog, NOT
  wired into `dossier.live-store`'s decorator chain. `->enrichment`
  returns the SAME shape `dossier.commoncrawl/->enrichment` does
  (`{:domain :has-web-presence? :latest-capture-date :latest-capture-
  status :source}`) so a caller can treat either source interchangeably
  without caring which one actually answered.

  ## Why this is a genuinely different, complementary source

  `dossier.commoncrawl` does a LIVE lookup against a general-purpose web
  crawl that may or may not have indexed any given small/niche site.
  This module reads OUR OWN already-fetched, LEI-keyed archive -- for
  the 109 companies these repos cover, coverage is real and 106/109
  were successfully checked (ADR-2607182500) which is denser than a
  general crawl is likely to be for the same set, at the cost of only
  covering companies this workspace has already registered a
  `cloud-itonami-lei-<LEI>` repo for (call `dossier.commoncrawl`
  directly for a company outside that set)."
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def raw-base-url "https://raw.githubusercontent.com/cloud-itonami")

(defn configured? [] true)

(defn- lei-of
  "Accepts either a bare LEI (`\"06BTX5UWZD0GQ5N5Y745\"`) or a
  dossier-canonical `lei-<LEI>` id (`dossier.gleif/->company`'s `:id`
  shape) -- always returns the bare, uppercase LEI."
  [lei-or-id]
  (str/upper-case (if (str/starts-with? lei-or-id "lei-") (subs lei-or-id 4) lei-or-id)))

(defn repo-name-of
  "The real `cloud-itonami-lei-*` repo naming convention (verified
  against the actual 109 repos, ADR-2607110300): lowercase LEI."
  [lei-or-id]
  (str "cloud-itonami-lei-" (str/lower-case (lei-of lei-or-id))))

(defn live-http-fn
  "The real fetch fn: a raw-content path -> the file's text body, or nil
  on any transport/not-found problem (never throws -- a company this
  workspace has no archive repo for is a normal, expected miss, not an
  error)."
  []
  (fn [path]
    (try
      (let [{:keys [status body error]} @(http/get (str raw-base-url path))]
        (when (and (not error) (= 200 status) body) body))
      (catch Exception _ nil))))

(defn fetch-blueprint
  "GET /<repo>/main/blueprint.edn -> the parsed company-identity map
  (`:company/legal-name`/`:company/lei`/`:company/jurisdiction`/
  `:company/website`/`:company/ticker`), or nil."
  [fetch-fn lei-or-id]
  (some-> (fetch-fn (str "/" (repo-name-of lei-or-id) "/main/blueprint.edn"))
          edn/read-string))

(defn fetch-site-journal
  "GET /<repo>/main/80-data/public/site.journal.edn -> the parsed quad
  vector, or nil (repo/file absent, or the ADR-2607182500 batch job
  hadn't reached this company yet)."
  [fetch-fn lei-or-id]
  (some-> (fetch-fn (str "/" (repo-name-of lei-or-id) "/main/80-data/public/site.journal.edn"))
          edn/read-string))

(defn latest-site-observation
  "Folds the quad-log (ADR-2607182500's `[entity attr value tx :add]`
  shape) down to the single most recent per-entity flat map --
  `:site/checked-at`'s value across the LAST entity id in the journal
  (a fresh entity id per re-check, so the last one IS the latest
  observation; no need to compare dates across entities). Returns nil
  on an empty/absent journal."
  [quads]
  (when (seq quads)
    (let [last-eid (nth (peek quads) 0)]
      (->> quads
           (filter #(= last-eid (nth % 0)))
           (reduce (fn [m [_ a v]] (assoc m a v)) {})))))

(defn ->enrichment
  "A folded site observation (or nil) + the source repo -> the SAME
  non-authoritative enrichment shape `dossier.commoncrawl/->enrichment`
  returns. `domain` is the observation's own `:site/url` when present
  (never re-derived from `blueprint.edn` -- the journal entry is the
  actual thing that was checked)."
  [lei-or-id observation]
  {:domain (:site/url observation)
   :has-web-presence? (boolean (and observation (= 200 (:site/http-status observation))))
   :latest-capture-date (:site/checked-at observation)
   :latest-capture-status (:site/http-status observation)
   :source {:class :public-web-crawl
            :ref (str "https://github.com/cloud-itonami/" (repo-name-of lei-or-id)
                      "/blob/main/80-data/public/site.journal.edn")}})

(defn enrichment-for
  "One-call convenience: `fetch-fn` + a LEI/dossier-id -> `->enrichment`'s
  result, or nil if this workspace has no archive repo for that LEI at
  all (never a guess -- distinct from `has-web-presence? false`, which
  means 'we checked and it didn't respond')."
  [fetch-fn lei-or-id]
  (when-let [quads (fetch-site-journal fetch-fn lei-or-id)]
    (->enrichment lei-or-id (latest-site-observation quads))))
