(ns dossier.identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.identity :as id]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(deftest identity-bootstrap-test
  (testing "load-or-create-identity! generates a new identity on first run"
    (let [id-file (io/file ".dossier" "identity.edn")]
      (when (.exists id-file) (.delete id-file))
      (let [id1 (id/load-or-create-identity!)]
        (is (contains? id1 :did-key) "identity should have did-key")
        (is (contains? id1 :pub-key-b64) "identity should have pub-key-b64")
        (is (contains? id1 :priv-key-b64) "identity should have priv-key-b64")
        (is (string? (:did-key id1)) "did-key should be a string")
        (is (.startsWith (:did-key id1) "did:key:") "did-key should start with did:key:"))))

  (testing "load-or-create-identity! persists to .dossier/identity.edn"
    (let [id-file (io/file ".dossier" "identity.edn")]
      (is (.exists id-file) "identity.edn should be persisted")))

  (testing "load-or-create-identity! returns same identity on subsequent calls"
    (let [id2 (id/load-or-create-identity!)
          id3 (id/load-or-create-identity!)]
      (is (= (:did-key id2) (:did-key id3)) "identity should persist across calls")
      (is (= (:priv-key-b64 id2) (:priv-key-b64 id3)) "private key should persist"))))
