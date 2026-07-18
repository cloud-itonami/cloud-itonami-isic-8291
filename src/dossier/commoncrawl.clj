(ns dossier.commoncrawl
  "Real Common Crawl Index Server client (ADR-2607182400) -- a
  DISCOVERY/ENRICHMENT utility, deliberately NOT a `dossier.facts` source-
  basis entry and NOT wired into `dossier.store`/`dossier.live-store` at
  all. JVM-only plain `.clj`, same `org.httpkit.client` + `jsonista` pair
  `dossier.companies-house` uses.

  ## Why this is architecturally separate from the registry sources

  `dossier.facts`'s catalog is specifically 'the ONLY source classes the
  DisclosureGovernor will accept as a citation for a company/official/
  relationship fact' -- every existing entry (GLEIF, Companies House, SEC
  EDGAR, houjin-bangou) is a government/consortium-maintained AUTHORITATIVE
  registry. Common Crawl is a raw, unmoderated public web crawl: it can
  confirm 'this domain had a page at this URL on this date', never
  'this is a real, currently-registered company'. Treating a crawl hit
  as registry-grade evidence would be exactly the kind of fabricated-
  authority this actor's whole governed design exists to prevent -- so
  this module deliberately has NO `->company` mapper, is NEVER merged
  into a `dossier.store` Company record's `:flags` (which is itself
  governed/disclosure-gated per `dossier.policy`'s tier-column table, a
  further reason not to conflate a web-crawl signal with a compliance
  flag), and is invisible to `dossier.live-store`'s decorator chain. A
  caller who wants both a registry fact AND a web-presence signal calls
  this module and a registry module (`dossier.houjin-bangou` etc.)
  separately and keeps their results separate.

  ## What this IS good for

  Discovery-by-domain-verification (given a candidate company/domain you
  already have from another source -- e.g. a name/domain surfaced by a
  government registry search or supplied by an operator -- confirm it
  has an actual, crawled public web presence, and when) -- NOT discovery-
  by-industry/keyword: the real Common Crawl Index API is URL-keyed ONLY
  (verified live in this session, ADR-2607182400) -- there is no `q=`
  full-text/content search parameter at all; querying without `url=`
  returns `{\"message\": \"The \\\"url\\\" param is required\"}`. A small
  local business's site may also simply not be in ANY given monthly
  crawl (Common Crawl prioritizes broadly-linked domains) -- a miss here
  is NOT evidence a company doesn't exist, only that this specific crawl
  didn't happen to capture it.

  Keyless -- no auth, no registration, no Application-ID wait (unlike
  `dossier.houjin-bangou`). `configured?` always returns true; kept for
  interface symmetry with the other client modules, not because a key
  is ever needed."
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            [jsonista.core :as j]))

(def base-url "https://index.commoncrawl.org")

(defn configured? [] true)

(defn live-http-fn
  "The real http-kit-backed fetch fn: `{:path :query}` -> parsed capture
  records vector, or nil on a genuine transport/rate-limit problem (never
  throws), same discipline as `dossier.companies-house/live-http-fn`.

  Real-API quirk verified live in this session (ADR-2607182400): a
  genuine no-match response is HTTP **404**, not 200, WITH a real
  parseable JSON body (`{\"message\": \"No Captures found for: ...\"}}`) --
  this is Common Crawl's own way of saying 'confirmed absent from this
  crawl', not a transport failure, so this fn parses the body on 404
  exactly like on 200. Only other status codes / a transport `error` /
  an empty body degrade to nil (genuine 'we don't know'). A multi-match
  response is newline-delimited JSON (one capture record per line); this
  fn parses each line into the vector `captures-of` expects."
  []
  (fn [{:keys [path query]}]
    (try
      (let [{:keys [status body error]}
            @(http/get (str base-url path) {:query-params query})]
        (when (and (not error) (#{200 404} status) body)
          (let [lines (remove str/blank? (str/split-lines body))]
            (mapv #(j/read-value % j/keyword-keys-object-mapper) lines))))
      (catch Exception _ nil))))

(defn latest-collection-id
  "The most recent monthly collection id (`\"CC-MAIN-YYYY-WW\"`) from
  `/collinfo.json` -- Common Crawl publishes a new one roughly monthly,
  no fixed schedule, so this is resolved live rather than hardcoded.
  `fetch-collections-fn` is `(fn [] collections-vector|nil)`, injectable
  for tests."
  [fetch-collections-fn]
  (some-> (fetch-collections-fn) first :id))

(defn live-collections-fn
  "Real fetch of `/collinfo.json` -> the parsed collections vector, or
  nil on any transport problem."
  []
  (fn []
    (try
      (let [{:keys [status body error]} @(http/get (str base-url "/collinfo.json"))]
        (when (and (not error) (= 200 status) body)
          (j/read-value body j/keyword-keys-object-mapper)))
      (catch Exception _ nil))))

(defn captures-of
  "GET /{collection-id}-index?url={url}&output=json -- every capture
  record Common Crawl has for the EXACT `url` (not a domain-wide crawl;
  see ns docstring for why this is verification, not discovery) in
  `collection-id`. Returns an empty vector on a genuine no-match (the
  real API's own `{\"message\": \"No Captures found for: ...\"}` response),
  nil on any transport/error problem -- these are deliberately different
  return shapes so a caller can tell 'confirmed absent from this crawl'
  apart from 'we don't actually know'."
  [fetch-fn collection-id url]
  (let [result (fetch-fn {:path (str "/" collection-id "-index")
                          :query {"url" url "output" "json"}})]
    (cond
      (nil? result) nil
      (and (= 1 (count result)) (:message (first result))) []
      :else result)))

(defn latest-capture
  "The most recent capture record for `url` in `collection-id` (captures
  come back newest-first is NOT guaranteed by the API, so this sorts by
  `:timestamp` explicitly), or nil if none."
  [fetch-fn collection-id url]
  (some->> (captures-of fetch-fn collection-id url)
           seq
           (sort-by :timestamp)
           last))

(defn has-web-presence?
  "True iff `url` has at least one capture in `collection-id`. A `false`
  here (or `captures-of` returning `[]`) is honestly labeled 'not found
  in THIS crawl', never asserted as 'this company has no website' -- see
  ns docstring."
  [fetch-fn collection-id url]
  (boolean (seq (captures-of fetch-fn collection-id url))))

(defn ->enrichment
  "A capture record (or nil) -> a small, explicitly NON-authoritative
  enrichment map. Deliberately NOT a `dossier.store` Company shape (no
  `:id`/`:legal-name`/`:jurisdiction`) -- this can never be mistaken for
  a registry fact by a caller who mixes up mapper functions."
  [url capture]
  {:domain url
   :has-web-presence? (some? capture)
   :latest-capture-date (some-> capture :timestamp)
   :latest-capture-status (some-> capture :status)
   :source {:class :public-web-crawl :ref (str base-url "/")}})
