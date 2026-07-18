(ns dossier.edge.cacao-mint
  "CAIP-122/SIWE CACAO mint for the edge — the missing inverse of
  dossier.edge.cacao/verify. Existed only on the JVM until now
  (cacao.core/mint, orgs/kotoba-lang/cacao); cacao.cljc's ns docstring
  explicitly says this edge Function 'never mints a CACAO, only checks one
  a client presents' — that was true until dossier.edge.webauthn
  needed to mint a CACAO on a user's behalf right after a WebAuthn
  assertion verifies (see that ns for why: WebAuthn signs authenticatorData
  + a clientDataJSON hash, not an arbitrary app-chosen SIWE plaintext, so a
  passkey can never sign a CACAO directly — the server mints one, backed by
  a server-held-but-passkey-gated Ed25519 keypair per user, exactly once
  the passkey assertion is independently verified).

  Every byte-encoding piece here (base58 encode, CBOR map encode, SIWE
  plaintext construction) is a straight promotion of dossier.edge.
  wire-fixtures' test-only fixture builder — that code already round-trips
  through the real production `cacao/verify` in every cacao_test.cljc test,
  so this is not new untested crypto, just the first production call site.

  CLJS-only (js/crypto.subtle, js/Promise, js/btoa)."
  (:require [dossier.edge.base58 :as base58]
            [dossier.edge.cbor :as cbor]
            [dossier.edge.cacao :as cacao]))

(defn did-key-from-raw-ed25519-pub
  "did:key:z... (Ed25519, multicodec 0xed01) from a raw 32-byte public key —
  the mint-side inverse of cacao.cljc's private `did-key->pubkey`."
  [raw-pub-bytes]
  (str "did:key:z" (base58/encode (js/Uint8Array.from
                                   (into [0xed 0x01] (array-seq (js/Array.from raw-pub-bytes)))))))

(defn bytes->base64 [bytes]
  (let [arr (js/Array.from bytes)]
    (js/btoa (apply str (map js/String.fromCharCode (array-seq arr))))))

(defn mint
  "Sign `fields` (:domain :aud :version :nonce :iat :exp :resources — all
  strings except :resources, a vector-of-strings or nil) as `iss` using
  `sign-fn` (a fn of msg-bytes -> Promise<sig-bytes>, e.g. `#(js/crypto.
  subtle.sign \"Ed25519\" priv-key %)`), and assemble the base64 CACAO blob
  `cacao/verify` accepts unmodified. Returns a Promise<{:cacao-b64 :iss}>.

  Takes `iss` as an explicit caller-supplied string rather than deriving it
  from an exportable keypair — dossier.edge.webauthn's login path
  signs with an unwrapped (decrypted-in-memory, non-exportable) private
  key it already knows the `did` for from KV, so there is no keypair object
  to export a public key from at that point. The registration path (which
  DOES hold a fresh, exportable keypair) derives `iss` itself via
  `did-key-from-raw-ed25519-pub` before calling this."
  [iss sign-fn fields]
  (let [payload #js {:iss iss
                      :aud (:aud fields)
                      :iat (:iat fields)
                      :exp (:exp fields)
                      :nonce (:nonce fields)
                      :domain (:domain fields)
                      :version (or (:version fields) "1")
                      :resources (clj->js (or (:resources fields) []))}
        msg (cacao/siwe-message payload)
        msg-bytes (.encode (js/TextEncoder.) msg)]
    (-> (sign-fn msg-bytes)
        (.then
         (fn [sig-ab]
           (let [sig-b64 (bytes->base64 (js/Uint8Array. sig-ab))
                 p-pairs (cond-> [["iss" iss]
                                  ["aud" (:aud fields)]
                                  ["iat" (:iat fields)]
                                  ["nonce" (:nonce fields)]
                                  ["domain" (:domain fields)]
                                  ["version" (or (:version fields) "1")]]
                           (:exp fields) (conj ["exp" (:exp fields)])
                           (seq (:resources fields)) (conj ["resources" (vec (:resources fields))]))
                 outer (cbor/encode-cacao-envelope p-pairs sig-b64)]
             {:cacao-b64 (bytes->base64 (js/Uint8Array.from (clj->js outer)))
              :iss iss}))))))
