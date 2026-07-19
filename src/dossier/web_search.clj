(ns dossier.web-search
  "Real net-kotobase `ai.gftd.apps.kotobase.web.search` XRPC client
  (ADR-2607192200) -- the SECOND, still non-authoritative web-presence
  enrichment source alongside `dossier.commoncrawl` / `dossier.
  lei-site-archive`, but a DIFFERENT KIND of signal: those two answer
  'does this ONE known domain have a page, and when' (a single yes/no
  presence check); this ns answers 'which pages, across net-kotobase's
  own ingested corpus, match this keyword/company-name query' (a ranked,
  multi-hit keyword search). JVM-only plain `.clj` (`org.httpkit.client` +
  `jsonista`), same host-fn-injection shape and dependency-minimalism as
  every other `dossier.*` live-data client in this repo (`dossier.
  commoncrawl`, `dossier.companies-house`, `dossier.houjin-bangou`, ...).

  ## Role split vs `dossier.commoncrawl` (ADR-2607192200 decision item 8)

  `dossier.commoncrawl` calls Common Crawl's own Index API directly --
  a raw, general-purpose web crawl with NO keyword/full-text query
  parameter at all (URL-keyed only, see that ns's docstring). This ns
  calls net-kotobase's `web.search`, which searches a SEPARATE, smaller,
  deliberately-curated corpus: pages explicitly `web.ingest`-ed into
  kotobase.net (by this actor, by `kotoba-lang/kotobase-commoncrawl-actor`,
  or by any other ingester), tokenized/scored/snippeted at net-kotobase's
  edge. So this is NOT a second Common Crawl client and NOT a replacement
  for `dossier.commoncrawl` -- it is the 'richer entity web-presence
  lookup' complement the ADR calls for: free-text/company-name search
  over whatever has actually been ingested, at the cost of only covering
  that (currently small, growing) ingested set, exactly the honest
  scope-limitation the ADR documents (\"Search quality for anything
  outside the ingested seed domains remains zero\").

  ## Still deliberately kept OUT of the governed fact-basis system

  Same discipline as `dossier.commoncrawl`/`dossier.lei-site-archive`: a
  keyword hit against net-kotobase's corpus is a caller-ingested web
  signal, never a registry fact. This ns has no `->company` mapper, is
  NOT added to `dossier.facts`'s catalog, and is NOT wired into `dossier.
  live-store`'s decorator chain. Its result shapes (`search!`'s raw hits,
  `->presence-signal`'s summary) are DELIBERATELY different-keyed from
  `dossier.commoncrawl/lei-site-archive`'s `->enrichment` shape
  (`:domain`/`:has-web-presence?`/`:latest-capture-date`/
  `:latest-capture-status`) rather than force-fit into it -- a keyword
  search hit has no 'capture date/status' of its own (that concept
  belongs to a single-URL crawl-presence check, not a ranked multi-hit
  search), and this codebase's whole `->enrichment` discipline exists
  specifically so a caller can never mistake one source's shape for
  another's (see `dossier.commoncrawl`'s ns docstring). A caller wanting
  both signals calls `dossier.commoncrawl/has-web-presence?` AND this
  ns's `search!`/`->presence-signal` separately and keeps the two
  results distinct, exactly as it already does for `dossier.commoncrawl`
  vs `dossier.lei-site-archive`.

  ## Auth: CACAO self-mint via `dossier.kotobase-identity`

  net-kotobase's edge (`kotobase.worker/route-datomic-family`, confirmed
  by reading `clj-edge/src/kotobase/{worker,proxy}.cljc` before writing
  this ns) requires a resolved, non-nil `viewer` for EVERY `/xrpc/
  ai.gftd.apps.kotobase.*` route including `web.search` -- there is no
  anonymous/public read path. In production (`worker-b2-mode?` true),
  the only realistic non-interactive way to resolve a viewer is a real
  CACAO (`Authorization: CACAO <b64>` + `X-Kotoba-Did: <did>>`),
  verified by `proxy/verify-worker-b2-cacao`. So this actor self-mints
  its own CACAO via `dossier.kotobase-identity` (see that ns's docstring
  for why THIS identity module, not the pre-existing but non-functional
  `dossier.identity`/`dossier.edge.cacao*` from PR #3) -- no shared
  operator token, no owner hand-off.

  Wire shape (verified directly against net-kotobase's own edge code +
  lexicon, `contracts/lexicons/ai/gftd/apps/kotobase/web/search.json` and
  `clj-edge/src/kotobase/domain_search.cljc`'s `handle-web-search`):

    POST https://kotobase.net/xrpc/ai.gftd.apps.kotobase.web.search
    headers: Authorization: CACAO <cacao-b64>
             X-Kotoba-Did: <did>
             Content-Type: application/json
    body: {q db_name? graph? limit? query_embedding? cacao_b64?}
    -> {ok count candidates? graph? basis_t? results:[{url title snippet score}]}

  `query_embedding` (ADR-2607192200's vector-search addendum) and any
  future extracted-metadata pass-through fields on a result row are
  accepted/forwarded but never REQUIRED -- as of this writing net-
  kotobase's own `kotobase.web/rank-pages` only ever returns
  `{url title snippet score}` per hit (verified by reading that ns), so
  `search!` below decodes each result row as-is (whatever keys ARE
  present) rather than asserting a larger fixed shape, so this client
  keeps working unchanged the day the server starts returning
  `category`/`summary`/`entities` too.

  ## Live-verified against production (this session)

  Unlike a purely offline-tested client, this ns's full request/response
  wire shape was exercised live against production `kotobase.net`: a
  disposable test identity self-minted a CACAO via `dossier.kotobase-
  identity`, `web.ingest`-ed one throwaway page (verifying the SAME
  session/headers this ns builds are accepted by the real edge, `ok:true`
  with a real content-addressed commit CID back), then this ns's own
  `search!`/`->presence-signal` found that exact page by keyword query
  with a real, non-zero relevance `:score` and a real `:snippet` -- the
  complete self-mint -> auth -> ingest -> search round trip, not just a
  mocked assertion. One real, load-bearing finding from that live run:
  `web.search`'s `db_name` resolution is PER-TENANT (net-kotobase's
  `kotobase.graph/resolve-db-graph` hashes `\"kotobase/db/\" tenant-did
  \"/\" db-name`, confirmed by reading that ns) -- two different actor
  identities using the SAME `db_name` (e.g. the well-known default,
  `\"webpages\"`) do NOT share one corpus; each identity's default search
  only ever sees pages IT ITSELF ingested, unless a caller passes another
  identity's actual graph CID via the lexicon's `graph` param (and has
  read authorization for it). So this actor's own `search!` calls, using
  its own self-minted identity, search its OWN prior ingests by default
  -- searching another ingester's corpus (e.g. `kotoba-lang/
  kotobase-commoncrawl-actor`'s) needs that ingester's specific graph CID
  passed explicitly, which this ns's `graph` opt supports but does not
  itself discover."
  (:require [org.httpkit.client :as http]
            [jsonista.core :as j]
            [dossier.kotobase-identity :as kid]))

(def base-url "https://kotobase.net")
(def search-path "/xrpc/ai.gftd.apps.kotobase.web.search")

(defn search-url [] (str base-url search-path))

(defn configured?
  "Always true -- like `dossier.commoncrawl`, no externally-registered API
  key is needed (CACAO is self-minted locally by `dossier.kotobase-
  identity`); kept for interface symmetry with the other client modules."
  [] true)

(defn session
  "One-call convenience: load/create this actor's identity + mint a fresh
  short-lived kotobase session -- {:did :cacao-b64 :db-name :resources}.
  Callers doing many searches in one process should mint ONCE and reuse
  (a session is valid for `ttl-seconds`, default 1h) rather than call this
  per search."
  ([] (session {}))
  ([opts] (kid/mint-kotobase-session (kid/load-or-create-identity!) opts)))

;; -- auth headers + request body -------------------------------------------

(defn auth-headers
  "The two CACAO headers every call needs, plus content-type -- a plain
  map, so `http-fn` implementations can merge it straight into whatever
  request-options shape they use."
  [sess]
  (assoc (kid/auth-headers sess) "Content-Type" "application/json"))

(defn build-search-body
  "{:q :limit? :db-name? :graph? :query-embedding? :cacao-b64?} -> the
  JSON body map, snake_case field names per the lexicon. Only present
  (some?) optional keys are included -- omitting limit/db-name/graph/
  query-embedding is byte-identical to the original token-only search
  call shape."
  [{:keys [q limit db-name graph query-embedding cacao-b64]}]
  (cond-> {"q" q}
    (some? limit) (assoc "limit" limit)
    (some? db-name) (assoc "db_name" db-name)
    (some? graph) (assoc "graph" graph)
    (and (sequential? query-embedding) (seq query-embedding))
    (assoc "query_embedding" (vec query-embedding))
    (some? cacao-b64) (assoc "cacao_b64" cacao-b64)))

;; -- live http ---------------------------------------------------------------

(defn live-http-fn
  "The real http-kit-backed fetch fn: `{:url :headers :body}` (body a
  Clojure map, JSON-encoded here) -> `{:status :body}` (`:body` the
  already-JSON-parsed response map, or the raw string when the response
  isn't valid JSON -- e.g. an upstream HTML error page), or `{:status nil
  :error msg}` on a transport failure. Never throws, same discipline as
  every other `dossier.*` live-http-fn."
  []
  (fn [{:keys [url headers body]}]
    (try
      (let [{:keys [status body error]}
            @(http/post url {:headers headers :body (j/write-value-as-string body)})]
        (if error
          {:status nil :error (str error)}
          {:status status
           :body (try (j/read-value body j/keyword-keys-object-mapper)
                      (catch Exception _ body))}))
      (catch Exception e {:status nil :error (.getMessage e)}))))

;; -- web.search ---------------------------------------------------------------

(defn search!
  "POST a web.search query. `http-fn` is `(fn [{:keys [url headers body]}]
  -> {:status :body})` (production: `live-http-fn`; tests inject a fake
  recording/returning canned data). `sess` is a minted kotobase session
  (see `session`). Returns `{:ok true :count :candidates? :graph? :results
  [{:url :title :snippet :score ...}]}` on a parseable, `ok:true`
  response, `{:ok false :status? :error? :body?}` on any transport/auth/
  upstream-error/shape failure. Never throws."
  [http-fn sess q & [opts]]
  (try
    (let [body (build-search-body (merge {:q q} opts (select-keys sess [:cacao-b64])))
          {:keys [status body error]} (http-fn {:url (search-url)
                                                 :headers (auth-headers sess)
                                                 :body body})
          ok? (and (nil? error) status (<= 200 status 299) (map? body) (true? (:ok body)))]
      (if ok?
        (cond-> {:ok true
                 :count (:count body)
                 :results (vec (or (:results body) []))}
          (:candidates body) (assoc :candidates (:candidates body))
          (:graph body) (assoc :graph (:graph body))
          (:basis_t body) (assoc :basis-t (:basis_t body)))
        (cond-> {:ok false}
          status (assoc :status status)
          error (assoc :error error)
          (map? body) (assoc :body body))))
    (catch Exception e {:ok false :error (.getMessage e)})))

(defn ->presence-signal
  "A `search!` result (or a raw `q`, calling `search!` itself) -> a small
  summary map DELIBERATELY shaped unlike `dossier.commoncrawl/lei-site-
  archive`'s `->enrichment` (see ns docstring: a keyword-search hit list
  is not a single-URL capture check, and this codebase's whole enrichment
  discipline is to never let two different-meaning sources share a
  shape). `{:query :hit-count :has-hits? :top-hits :source}` --
  `:top-hits` keeps at most `top-n` (default 5) full hit maps (url/title/
  snippet/score, plus any extra fields the server returns) so a caller
  gets a real, inspectable sample without every hit."
  ([http-fn sess q] (->presence-signal http-fn sess q {}))
  ([http-fn sess q {:keys [top-n] :or {top-n 5} :as opts}]
   (let [{:keys [ok results count] :as result} (search! http-fn sess q opts)]
     {:query q
      :hit-count (if ok (or count (clojure.core/count results) 0) 0)
      :has-hits? (boolean (and ok (seq results)))
      :top-hits (if ok (vec (take top-n results)) [])
      :source {:class :kotobase-search-index :ref (search-url)}
      :ok ok
      :error (:error result)})))
