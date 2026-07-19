(ns dossier.kotobase-identity
  "CACAO self-mint identity for this actor's calls to net-kotobase
  (ai.gftd.apps.kotobase.web.search / web.ingest, ADR-2607192200) -- the
  no-server-key authorization convention documented in skill build-actor:
  an actor holding its own Ed25519 seed is structurally authorized to
  self-mint a short-lived CACAO for its OWN did:key, no owner hand-off or
  shared operator token required.

  ## Why this is a NEW ns, not a reuse of dossier.identity

  This repo already merged a dossier.identity / dossier.edge.cacao* identity
  module (PR #3, 'CACAO self-mint identity + StateGraph tests'). Reusing it
  was the FIRST thing checked here (per this task's own standing instruction
  to reuse a shared actor identity mechanism rather than build a second one)
  -- but inspection found it is not actually a working CACAO mechanism yet:

    - dossier.identity/load-or-create-identity!'s did-key-from-pubkey is
      explicitly documented as a 'rough approximation' -- it hashes the
      base64 pubkey string into a 10-digit number and formats
      \"did:key:z6Mk\" + digits, NOT a real multibase/multicodec (0xed01)
      encoding of the actual Ed25519 public key. net-kotobase's edge
      (ed25519.core/did-key->pubkey-equivalent verification) would reject
      this immediately as an invalid multibase did -- it is not
      cryptographically anything, just a same-length-looking string.
    - dossier.edge.cacao / dossier.edge.cacao-mint / dossier.edge.base58 /
      dossier.edge.cbor are explicitly documented in their OWN ns
      docstrings as 'CLJS-only (js/crypto.subtle, js/Promise, js/btoa)' --
      this repo has no ClojureScript/shadow-cljs build at all (deps.edn is
      JVM-.clj-only, no :cljs/shadow-cljs config anywhere), so these
      namespaces cannot be loaded/compiled here, let alone called, from
      `clojure -M:test`/`-M:run`. dossier.identity itself never requires
      them either -- the two halves (fake did-key generator, unreachable
      real mint/verify) are disconnected.

  Net effect: dossier.identity cannot mint a CACAO net-kotobase's
  production edge would ever accept -- building on it here would silently
  reproduce exactly the kind of claimed-but-not-real integration
  ADR-2607192200 itself retracts (ADR-2607081500). So this ns is a
  SEPARATE, genuinely working identity mechanism, built the same way
  kotoba-lang/kotobase-commoncrawl-actor's commoncrawl.identity is (that ns
  mints CACAOs verified live against production kotobase.net in the same
  session this ADR was written) -- on the REAL portable cacao.core
  (kotoba-lang/org-chainagnostic-cacao, mint/verify) + ed25519.core
  (kotoba-lang/org-ietf-ed25519, did:key derivation) libraries, both
  already .cljc with a live-verified JVM branch. dossier.identity /
  dossier.edge.* are left completely untouched (out of scope for this
  task, may be under active repair elsewhere) -- this ns persists to its
  OWN file (.dossier/cloud-itonami-isic-8291-kotobase-identity.edn, same
  gitignored .dossier/ directory PR #3 already added) so the two never
  collide.

  The resource scope minted here follows
  commoncrawl.identity/kotobase-resources (kotoba-lang/
  kotobase-commoncrawl-actor) byte-for-byte, which itself follows
  cloud_itonami.identity_core/kotobase-resources byte-for-byte
  (kotoba://op/datom:read, kotoba://op/datom:transact,
  kotoba://can/kotobase:pin, kotoba://graph/<db-name>) -- that exact scope
  is what closed real live 401s against production backend.kotobase.net in
  a prior session; reusing it unchanged here minimizes the risk of a
  scope-mismatch rejection on this actor's first live call.

  SECURITY: the raw seed NEVER leaves load-or-create-identity!'s return
  map's use inside this process -- only the derived did:key and a minted,
  short-lived CACAO are ever handed to a caller/logged. The seed is
  persisted to .dossier/<actor>-kotobase-identity.edn, which MUST stay
  gitignored -- never commit it."
  (:require [cacao.core :as cacao]
            [ed25519.core :as ed]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(def default-actor "cloud-itonami-isic-8291")

(def default-kotobase-aud
  "net-kotobase's pod enforces aud == did:web:kotobase.net (confirmed,
  commoncrawl.identity/default-kotobase-aud) -- a mismatch is rejected
  with 'cacao audience mismatch'."
  "did:web:kotobase.net")

(def default-kotobase-domain "kotobase.net")

(def default-db-name
  "web.search/web.ingest's own default tenant db_name (kotobase.web
  lexicons) -- the same well-known per-tenant graph every caller gets by
  default, so a search against this default finds pages any other default
  ingester (e.g. kotobase-commoncrawl-actor) already put there."
  "webpages")

;; -- seed generation (fresh identity only) --------------------------------

(defn random-seed
  "32 cryptographically-random bytes for a fresh Ed25519 seed. :clj
  java.security.SecureRandom; :cljs Node's crypto.randomBytes."
  []
  #?(:clj (let [b (byte-array 32)]
            (.nextBytes (java.security.SecureRandom.) b)
            b)
     :cljs (js/Uint8Array. (.randomBytes (js/require "crypto") 32))))

(defn- b64->bytes [^String s]
  #?(:clj (.decode (java.util.Base64/getDecoder) s)
     :cljs (js/Uint8Array. (js/Buffer.from s "base64"))))

(defn- bytes->b64 [b]
  #?(:clj (.encodeToString (java.util.Base64/getEncoder) b)
     :cljs (.toString (js/Buffer.from b) "base64")))

;; -- persistence (.dossier/<actor>-kotobase-identity.edn, gitignored) -----

(defn identity-path [actor] (str ".dossier/" actor "-kotobase-identity.edn"))

(defn- ensure-dir! [dir]
  #?(:clj (.mkdirs (java.io.File. ^String dir))
     :cljs (let [fs (js/require "fs")]
             (when-not (.existsSync fs dir) (.mkdirSync fs dir #js {:recursive true})))))

(defn- read-identity-file [path]
  (try
    #?(:clj (when (.exists (java.io.File. ^String path))
              (edn/read-string (slurp path)))
       :cljs (let [fs (js/require "fs")]
               (when (.existsSync fs path)
                 (edn/read-string (.readFileSync fs path "utf8")))))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- write-identity-file! [path data]
  #?(:clj (spit path (pr-str data))
     :cljs (.writeFileSync (js/require "fs") path (pr-str data) "utf8")))

(defn load-or-create-identity!
  "Load .dossier/{actor}-kotobase-identity.edn (creating it, and its
  parent .dossier/ dir, on first use with a fresh random seed). Returns
  {:actor :did :seed-b64} -- :seed-b64 stays inside this process; only
  :did and a subsequently-minted CACAO are ever handed out."
  ([] (load-or-create-identity! default-actor))
  ([actor]
   (let [path (identity-path actor)
         existing (read-identity-file path)]
     (if-let [seed-b64 (:seed-b64 existing)]
       {:actor actor :did (or (:did existing) (ed/did-key-from-seed (b64->bytes seed-b64)))
        :seed-b64 seed-b64}
       (let [seed (random-seed)
             seed-b64 (bytes->b64 seed)
             did (ed/did-key-from-seed seed)]
         (ensure-dir! ".dossier")
         (write-identity-file! path {:actor actor :did did :seed-b64 seed-b64
                                     :created-at #?(:clj (str (java.time.Instant/now))
                                                    :cljs (.toISOString (js/Date.)))})
         {:actor actor :did did :seed-b64 seed-b64})))))

;; -- resource scope + timestamps ------------------------------------------

(defn kotobase-resources
  "CACAO resource scope for a kotobase.net graph read/write -- matches
  commoncrawl.identity/kotobase-resources byte-for-byte (see ns docstring
  for why kotoba://can/kotobase:pin specifically is non-optional)."
  [db-name]
  ["kotoba://op/datom:read"
   "kotoba://op/datom:transact"
   "kotoba://can/kotobase:pin"
   (str "kotoba://graph/" db-name)])

(defn iso8601-seconds
  "epoch-ms -> \"YYYY-MM-DDTHH:MM:SSZ\" -- the EXACT format
  kotobase-server's CACAO verifier requires (no fractional seconds; a bare
  .toISOString() -- which emits milliseconds -- would fail its regex and
  every CACAO this ns mints would be rejected as having an 'invalid CACAO
  iat/exp')."
  [epoch-ms]
  #?(:clj (-> (java.time.Instant/ofEpochMilli epoch-ms)
              (.truncatedTo java.time.temporal.ChronoUnit/SECONDS)
              .toString)
     :cljs (let [d (js/Date. epoch-ms)
                 pad #(.padStart (str %) 2 "0")]
             (str (.getUTCFullYear d) "-" (pad (inc (.getUTCMonth d))) "-" (pad (.getUTCDate d))
                  "T" (pad (.getUTCHours d)) ":" (pad (.getUTCMinutes d)) ":" (pad (.getUTCSeconds d))
                  "Z"))))

(defn now-ms [] #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))

(defn fresh-nonce
  "A random hex nonce -- MUST be fresh per mint (cacao.core/mint throws
  without one; the backend's nonce store rejects reuse)."
  []
  (let [bytes #?(:clj (let [b (byte-array 16)] (.nextBytes (java.security.SecureRandom.) b) b)
                 :cljs (.randomBytes (js/require "crypto") 16))]
    (ed/hexify bytes)))

;; -- mint ------------------------------------------------------------------

(defn mint-kotobase-session
  "identity ({:seed-b64 ...}, from load-or-create-identity!) + {:db-name
  :ttl-seconds} -> {:did :cacao-b64 :db-name :resources}. Mints a
  short-lived (default 1h -- this actor mints per-call, not per-day) CACAO
  scoped to (kotobase-resources db-name), aud/domain fixed to
  net-kotobase's pod requirements."
  [{:keys [seed-b64 did]} & [{:keys [db-name ttl-seconds] :or {db-name default-db-name ttl-seconds 3600}}]]
  (let [seed (b64->bytes seed-b64)
        now (now-ms)
        resources (kotobase-resources db-name)
        {:keys [cacao-b64 iss]} (cacao/mint
                                  {:seed seed
                                   :aud default-kotobase-aud
                                   :domain default-kotobase-domain
                                   :nonce (fresh-nonce)
                                   :iat (iso8601-seconds now)
                                   :exp (iso8601-seconds (+ now (* 1000 (long ttl-seconds))))
                                   :resources resources})]
    {:did (or did iss) :cacao-b64 cacao-b64 :db-name db-name :resources resources}))

(defn auth-headers
  "{:cacao-b64 :did} -> the two headers kotobase.net's edge gate requires
  (Authorization: CACAO <b64> + X-Kotoba-Did: <did>)."
  [{:keys [cacao-b64 did]}]
  {"Authorization" (str "CACAO " cacao-b64)
   "X-Kotoba-Did" did})
