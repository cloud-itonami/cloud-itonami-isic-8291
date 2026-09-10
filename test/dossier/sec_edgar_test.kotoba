(ns dossier.sec-edgar-test
  "dossier.sec-edgar exercised entirely offline via an injected fake
  fetch-fn (canned JSON-shaped maps mirroring the real SEC EDGAR
  `submissions` response — the exact top-level field set was confirmed
  2026-07-15 against the real production API, `curl -H \"User-Agent: ...\"
  https://data.sec.gov/submissions/CIK0000320193.json`, before this client
  was written) — no network access needed, same discipline as
  `dossier.companies-house-test`/`dossier.gleif-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.sec-edgar :as sec]))

(defn- fake-fetch
  "A minimal stand-in for `sec/live-http-fn`: dispatches on `:path`
  (ignoring the User-Agent header entirely, since this never makes a real
  request)."
  [routes]
  (fn [{:keys [path query]}]
    (when-let [f (get routes path)]
      (f query))))

(def meridian-submission
  "Fictitious submissions response, shaped exactly like SEC EDGAR's real
  JSON (cik/entityType/sic/sicDescription/name/tickers/exchanges/
  stateOfIncorporation/addresses/formerNames/... — only the subset this
  client actually maps is exercised in depth, the rest is present to prove
  the mapper ignores it rather than choking on it)."
  {:cik "0000123456"
   :entityType "operating"
   :sic "3674"
   :sicDescription "Semiconductors & Related Devices"
   :name "Meridian Robotics Corp (demo)"
   :tickers ["MRDN"]
   :exchanges ["Nasdaq"]
   :ein "123456789"
   :stateOfIncorporation "DE"
   :stateOfIncorporationDescription "DE"
   :addresses {:mailing {:street1 "1 Meridian Way (demo)" :city "Newark" :stateOrCountry "DE"}}
   :formerNames [{:name "MERIDIAN INDUSTRIAL CORP" :from "2001-01-01T00:00:00.000Z" :to "2015-01-01T00:00:00.000Z"}]
   :filings {:recent {}}})

(defn- meridian-routes []
  {"/submissions/CIK0000123456.json"
   (fn [_] meridian-submission)})

(deftest configured?-reflects-whether-a-user-agent-is-present
  (is (false? (sec/configured? nil)))
  (is (false? (sec/configured? "")))
  (is (true? (sec/configured? "dossier-test test@example.com"))))

(deftest submissions-pads-a-bare-cik-to-the-10-digit-path
  (testing "SEC's path requires zero-padded 10 digits regardless of how the caller spelled the CIK"
    (let [fetch (fake-fetch (meridian-routes))]
      (is (= meridian-submission (sec/submissions fetch 123456))
          "bare int")
      (is (= meridian-submission (sec/submissions fetch "123456"))
          "unpadded string")
      (is (= meridian-submission (sec/submissions fetch "0000123456"))
          "already zero-padded string (the form SEC's own :cik field uses)"))))

(deftest company-maps-to-dossier-company-shape
  (let [fetch (fake-fetch (meridian-routes))
        c (sec/->company (sec/submissions fetch "0000123456"))]
    (is (= "usa-0000123456" (:id c)))
    (is (= "Meridian Robotics Corp (demo)" (:legal-name c)))
    (is (= :usa (:jurisdiction c)))
    (is (= "0000123456" (:registration-no c)))
    (is (= :active (:status c))
        "no explicit status field exists on this endpoint -- :active is a stated assumption, not derived")
    (is (= :regulatory-filing (get-in c [:source :class]))
        "matches dossier.facts's :usa-sec-edgar catalog entry class")
    (is (= "https://data.sec.gov/submissions/CIK0000123456.json" (get-in c [:source :ref])))))

(deftest missing-submission-or-transport-failure-returns-nil-never-throws
  (let [always-nil (fn [_] nil)]
    (is (nil? (sec/submissions always-nil "0000123456")))
    (is (nil? (sec/->company nil)))))
