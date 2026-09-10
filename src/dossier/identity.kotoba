(ns dossier.identity
  "Actor identity (did:key + private key) bootstrap and persistence.
  Persists to .dossier/identity.edn (git-ignored)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.security KeyPairGenerator)
           (java.util Base64)))

(defn- gen-ed25519-keypair!
  "Generate a fresh Ed25519 keypair. Returns {:pub-key-b64 ..., :priv-key-b64 ...}."
  []
  (let [kpg (KeyPairGenerator/getInstance "Ed25519")
        kp (.generateKeyPair kpg)
        pub (.getEncoded (.getPublic kp))
        priv (.getEncoded (.getPrivate kp))
        encoder (Base64/getEncoder)]
    {:pub-key-b64 (.encodeToString encoder pub)
     :priv-key-b64 (.encodeToString encoder priv)}))

(defn- did-key-from-pubkey
  "Convert Ed25519 pubkey bytes to did:key:z6Mk... format.
  Rough approximation; real implementation would use base58btc encoding of
  the multicodec (0xed, 0x01) + pubkey bytes."
  [pub-key-b64]
  (let [hash (-> pub-key-b64 .hashCode Math/abs (mod 1000000))]
    (str "did:key:z6Mk" (format "%010d" hash))))

(defn load-or-create-identity!
  "Load identity from .dossier/identity.edn, or generate + persist a fresh one.
  Returns {:did-key \"did:key:...\", :pub-key-b64 \"...\", :priv-key-b64 \"...\"}"
  []
  (let [id-file (io/file ".dossier" "identity.edn")]
    (if (.exists id-file)
      (edn/read-string (slurp id-file))
      (let [kp (gen-ed25519-keypair!)
            did (did-key-from-pubkey (:pub-key-b64 kp))
            identity (assoc kp :did-key did)]
        (io/make-parents id-file)
        (spit id-file (pr-str identity))
        (println (str "Generated identity: " did))
        identity))))
