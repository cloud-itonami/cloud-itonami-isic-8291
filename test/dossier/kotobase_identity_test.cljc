(ns dossier.kotobase-identity-test
  "CACAO self-mint identity for net-kotobase calls (ADR-2607192200): seed
  generation/persistence/reload, and a minted session's shape/resources --
  exercised entirely offline (no network access needed). Mirrors
  kotoba-lang/kotobase-commoncrawl-actor's `commoncrawl.identity-test`
  (the reference this ns's design is ported from)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dossier.kotobase-identity :as kid]
            [cacao.core :as cacao]))

(def ^:private test-actor "kotobase-identity-test-actor")

(defn- cleanup! [actor]
  (let [path (kid/identity-path actor)]
    (try
      #?(:clj (let [f (java.io.File. ^String path)]
                (when (.exists f) (.delete f)))
         :cljs (let [fs (js/require "fs")]
                 (when (.existsSync fs path) (.unlinkSync fs path))))
      (catch #?(:clj Exception :cljs :default) _ nil))))

(deftest load-or-create-identity-creates-then-reloads-the-same-did
  (cleanup! test-actor)
  (try
    (let [id1 (kid/load-or-create-identity! test-actor)
          id2 (kid/load-or-create-identity! test-actor)]
      (is (str/starts-with? (:did id1) "did:key:z"))
      (is (= (:did id1) (:did id2)) "reload must derive the SAME did — the seed persisted, not a new one")
      (is (= (:seed-b64 id1) (:seed-b64 id2))))
    (finally (cleanup! test-actor))))

(deftest random-seed-is-32-bytes-and-not-constant
  (let [a (kid/random-seed) b (kid/random-seed)]
    (is (= 32 #?(:clj (count a) :cljs (.-length a))))
    (is (not= (vec a) (vec b)) "two calls must not return the same seed")))

(deftest kotobase-resources-includes-the-pin-capability
  (testing "the exact capability string that closed real live 401s against backend.kotobase.net
           in kotobase-commoncrawl-actor's commoncrawl.identity — reused unchanged here"
    (is (some #{"kotoba://can/kotobase:pin"} (kid/kotobase-resources "webpages")))
    (is (some #{"kotoba://op/datom:read"} (kid/kotobase-resources "webpages")))
    (is (some #{"kotoba://op/datom:transact"} (kid/kotobase-resources "webpages")))
    (is (some #{"kotoba://graph/webpages"} (kid/kotobase-resources "webpages")))))

(deftest iso8601-seconds-has-no-fractional-seconds
  (testing "the exact format kotobase-server's CACAO verifier requires -- a
           bare toISOString() (which emits milliseconds) would be rejected"
    (is (re-matches #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$" (kid/iso8601-seconds (kid/now-ms))))))

(deftest fresh-nonce-is-fresh-every-call
  (is (not= (kid/fresh-nonce) (kid/fresh-nonce))))

(deftest mint-kotobase-session-produces-a-verifiable-cacao
  (cleanup! test-actor)
  (try
    (let [id (kid/load-or-create-identity! test-actor)
          sess (kid/mint-kotobase-session id {:db-name "webpages" :ttl-seconds 3600})
          verified (cacao/verify (:cacao-b64 sess))]
      (is (= (:did id) (:did sess)))
      (is (:valid? verified) "a session this actor mints must verify under cacao.core/verify")
      (is (= (:did id) (:iss verified)))
      (is (= (kid/kotobase-resources "webpages") (:resources sess))))
    (finally (cleanup! test-actor))))

(deftest auth-headers-shape
  (is (= {"Authorization" "CACAO abc123" "X-Kotoba-Did" "did:key:z6Mk..."}
         (kid/auth-headers {:cacao-b64 "abc123" :did "did:key:z6Mk..."}))))

(deftest default-actor-is-this-actors-own-name
  (is (= "cloud-itonami-isic-8291" kid/default-actor)))

(deftest identity-path-is-distinct-from-the-pre-existing-dossier-identity-file
  (testing "must not collide with the pre-existing (PR #3) dossier.identity's
           .dossier/identity.edn -- see ns docstring for why this ns exists
           separately"
    (is (not= ".dossier/identity.edn" (kid/identity-path "cloud-itonami-isic-8291")))))
