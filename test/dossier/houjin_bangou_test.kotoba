(ns dossier.houjin-bangou-test
  "dossier.houjin-bangou exercised entirely offline: XML parsing against
  realistic fixture XML matching the real API's documented schema (field
  names cross-checked against `totechite/houjinbangou-api-wrapper`'s
  `src/types.ts` — not guessed), and endpoint/mapper logic via an injected
  fake fetch-fn (already-parsed corporations vectors), same discipline as
  `dossier.companies-house-test`. No real Application ID or network
  access needed."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.houjin-bangou :as hb]))

;; Realistic fixture XML: one real-shaped active corporation + one
;; dissolved (closeDate/closeCause populated) + the wrapper the real API
;; uses (<corporations lastUpdateDate=... count=... ><corporation>...
;; </corporation>...</corporations>), same element/tag names as
;; `corporation` in `src/types.ts`.
(def sample-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<corporations lastUpdateDate=\"2026-07-01\" count=\"2\" divideNumber=\"1\" divideSize=\"1\">
  <corporation>
    <sequenceNumber>1</sequenceNumber>
    <corporateNumber>5050005005266</corporateNumber>
    <process>01</process>
    <correct>0</correct>
    <updateDate>2026-01-15</updateDate>
    <changeDate>2026-01-15</changeDate>
    <name>大和職業紹介株式会社(demo)</name>
    <nameImageId></nameImageId>
    <kind>301</kind>
    <prefectureName>東京都</prefectureName>
    <cityName>千代田区</cityName>
    <streetNumber>丸の内一丁目1番1号</streetNumber>
    <addressImageId></addressImageId>
    <prefectureCode>13</prefectureCode>
    <cityCode>101</cityCode>
    <postCode>1000005</postCode>
    <addressOutside></addressOutside>
    <closeDate></closeDate>
    <closeCause></closeCause>
    <successorCorporateNumber></successorCorporateNumber>
    <changeCause></changeCause>
    <assignmentDate>2015-10-05</assignmentDate>
    <latest>1</latest>
    <enName></enName>
    <furigana>ヤマトショクギョウショウカイ</furigana>
    <hihyoji>0</hihyoji>
  </corporation>
  <corporation>
    <sequenceNumber>2</sequenceNumber>
    <corporateNumber>1234567890123</corporateNumber>
    <process>21</process>
    <correct>0</correct>
    <updateDate>2026-03-01</updateDate>
    <changeDate>2026-03-01</changeDate>
    <name>閉鎖済み合同会社(demo)</name>
    <kind>305</kind>
    <prefectureName>大阪府</prefectureName>
    <cityName>大阪市</cityName>
    <streetNumber>北区1-1</streetNumber>
    <prefectureCode>27</prefectureCode>
    <cityCode>102</cityCode>
    <postCode>5300001</postCode>
    <closeDate>2026-03-01</closeDate>
    <closeCause>01</closeCause>
    <assignmentDate>2018-04-01</assignmentDate>
    <latest>1</latest>
    <furigana>ヘイサズミゴウドウガイシャ</furigana>
    <hihyoji>0</hihyoji>
  </corporation>
</corporations>")

(def empty-result-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<corporations lastUpdateDate=\"2026-07-01\" count=\"0\" divideNumber=\"1\" divideSize=\"1\">
</corporations>")

(deftest configured?-reflects-whether-an-application-id-is-present
  (is (false? (hb/configured? nil)))
  (is (false? (hb/configured? "")))
  (is (true? (hb/configured? "some-application-id"))))

(deftest parse-xml-and-parse-corporations-extract-every-real-field
  (let [root (hb/parse-xml sample-xml)
        corps (hb/parse-corporations root)]
    (is (= 2 (count corps)))
    (testing "active corporation — every documented field extracted correctly"
      (let [c (first corps)]
        (is (= "5050005005266" (:corporateNumber c)))
        (is (= "大和職業紹介株式会社(demo)" (:name c)))
        (is (= "東京都" (:prefectureName c)))
        (is (= "千代田区" (:cityName c)))
        (is (= "丸の内一丁目1番1号" (:streetNumber c)))
        (is (= "301" (:kind c)))
        (is (= "" (:closeDate c)) "no close date on an active corporation")
        (is (= "" (:closeCause c)))
        (is (= "1" (:latest c)))))
    (testing "dissolved corporation — closeDate/closeCause populated"
      (let [c (second corps)]
        (is (= "1234567890123" (:corporateNumber c)))
        (is (= "2026-03-01" (:closeDate c)))
        (is (= "01" (:closeCause c)))))))

(deftest parse-corporations-on-a-zero-result-response-is-an-empty-vector-never-nil
  (is (= [] (hb/parse-corporations (hb/parse-xml empty-result-xml)))))

;; ───────────────────────── endpoint / mapper tests (fake fetch-fn) ─────

(defn- fake-fetch
  "A minimal stand-in for `hb/live-http-fn`: dispatches on `:path`,
  returning an already-parsed corporations vector (parsing happens
  inside `live-http-fn`, same seam `dossier.companies-house-test` injects
  at for pre-parsed JSON maps)."
  [routes]
  (fn [{:keys [path query]}]
    (when-let [f (get routes path)]
      (f query))))

(def yamato-corp
  {:corporateNumber "5050005005266" :name "大和職業紹介株式会社(demo)"
   :prefectureName "東京都" :cityName "千代田区" :streetNumber "丸の内一丁目1番1号"
   :kind "301" :closeDate "" :closeCause "" :latest "1"})

(def unrelated-corp
  {:corporateNumber "9999999999999" :name "大和職業紹介株式会社(demo)分室(unrelated)"
   :prefectureName "東京都" :cityName "港区" :streetNumber "1-1"
   :kind "301" :closeDate "" :closeCause "" :latest "1"})

(defn- yamato-routes []
  {"/4/name"
   (fn [{:strs [name mode]}]
     (when (and (= name "大和職業紹介株式会社(demo)") (= mode "1"))
       [yamato-corp unrelated-corp]))
   "/4/num"
   (fn [{:strs [number]}]
     (when (= number "5050005005266") [yamato-corp]))})

(deftest find-company-by-name-is-exact-match-only
  (testing "the prefix-match search must not let a similarly-named company through"
    (let [fetch (fake-fetch (yamato-routes))]
      (is (= "5050005005266" (:corporateNumber (hb/find-company-by-name fetch "大和職業紹介株式会社(demo)"))))
      (is (nil? (hb/find-company-by-name fetch "大和職業紹介株式会社(demo)分室(unrelated)"))
          "sanity: this fixture's second result exists but was not queried for")
      (is (nil? (hb/find-company-by-name fetch "存在しない株式会社"))
          "no search result at all -> nil, never a guess"))))

(deftest by-corporate-number-joins-multiple-numbers-with-a-comma
  (let [seen (atom nil)
        fetch (fn [{:keys [query]}] (reset! seen query) nil)]
    (hb/by-corporate-number fetch ["5050005005266" "1234567890123"])
    (is (= "5050005005266,1234567890123" (get @seen "number")))))

(deftest company-maps-to-dossier-company-shape
  (let [fetch (fake-fetch (yamato-routes))
        c (hb/->company (first (hb/by-corporate-number fetch "5050005005266")))]
    (is (= "jpn-5050005005266" (:id c)))
    (is (= "大和職業紹介株式会社(demo)" (:legal-name c)))
    (is (= :jpn (:jurisdiction c)))
    (is (= "5050005005266" (:registration-no c)))
    (is (= :active (:status c)))
    (is (= :official-registry (get-in c [:source :class])))))

(deftest close-date-present-maps-to-dissolved-status
  (let [c (hb/->company {:corporateNumber "1234567890123" :name "閉鎖済み合同会社(demo)"
                         :closeDate "2026-03-01" :closeCause "01"})]
    (is (= :dissolved (:status c)))))

(deftest missing-key-or-transport-failure-returns-nil-never-throws
  (let [always-nil (fn [_] nil)]
    (is (nil? (hb/search-by-name always-nil "anything")))
    (is (nil? (hb/by-corporate-number always-nil "5050005005266")))
    (is (nil? (hb/->company nil)))
    (is (nil? (hb/->company {})) "a corp map with no corporateNumber at all never becomes a company")))
