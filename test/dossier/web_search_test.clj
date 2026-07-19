(ns dossier.web-search-test
  "dossier.web-search exercised entirely offline via an injected fake
  http-fn (canned {:status :body} responses mimicking net-kotobase's real
  web.search JSON shape) -- same discipline as `dossier.commoncrawl-test`.
  No network access needed."
  (:require [clojure.test :refer [deftest is testing]]
            [dossier.web-search :as ws]))

(def sess {:cacao-b64 "cacao123" :did "did:key:z6MkTestDid" :db-name "webpages"})

(defn- fake-http
  "Records the request it received in `captured`, returns `response` (a
  `{:status :body}` map) regardless of what was sent -- the seam
  `search!` needs, same idiom `commoncrawl.kotobase-test` uses for
  `commoncrawl.kotobase/ingest!`/`search!`."
  [captured response]
  (fn [req] (reset! captured req) response))

;; -- configured? / search-url -------------------------------------------

(deftest configured?-is-always-true-keyless
  (is (true? (ws/configured?))))

(deftest search-url-shape
  (is (= "https://kotobase.net/xrpc/ai.gftd.apps.kotobase.web.search" (ws/search-url))))

;; -- auth-headers / build-search-body ------------------------------------

(deftest auth-headers-has-cacao-and-did-and-content-type
  (let [h (ws/auth-headers sess)]
    (is (= "CACAO cacao123" (get h "Authorization")))
    (is (= "did:key:z6MkTestDid" (get h "X-Kotoba-Did")))
    (is (= "application/json" (get h "Content-Type")))))

(deftest build-search-body-only-q-is-the-minimal-shape
  (is (= {"q" "yamato shokugyou"} (ws/build-search-body {:q "yamato shokugyou"}))))

(deftest build-search-body-includes-present-optional-fields-only
  (let [body (ws/build-search-body {:q "gleif" :limit 5 :db-name "webpages"
                                     :graph "bafy..." :query-embedding [0.1 0.2]
                                     :cacao-b64 "c1"})]
    (is (= "gleif" (get body "q")))
    (is (= 5 (get body "limit")))
    (is (= "webpages" (get body "db_name")))
    (is (= "bafy..." (get body "graph")))
    (is (= [0.1 0.2] (get body "query_embedding")))
    (is (= "c1" (get body "cacao_b64")))))

(deftest build-search-body-empty-embedding-is-omitted-not-an-empty-array
  (let [body (ws/build-search-body {:q "x" :query-embedding []})]
    (is (not (contains? body "query_embedding")))))

;; -- search! --------------------------------------------------------------

(deftest search!-sends-the-expected-request
  (let [captured (atom nil)
        http (fake-http captured {:status 200 :body {:ok true :count 0 :results []}})]
    (ws/search! http sess "yamato shokugyou")
    (is (= (ws/search-url) (:url @captured)))
    (is (= "CACAO cacao123" (get-in @captured [:headers "Authorization"])))
    (is (= "did:key:z6MkTestDid" (get-in @captured [:headers "X-Kotoba-Did"])))
    (is (= "yamato shokugyou" (get-in @captured [:body "q"])))
    (is (= "cacao123" (get-in @captured [:body "cacao_b64"])) "the session's cacao rides in the body too")))

(deftest search!-parses-a-real-shaped-2xx-response
  (let [http (fake-http (atom nil)
                        {:status 200
                         :body {:ok true :count 2 :candidates 40 :graph "bafygraph"
                                :results [{:url "https://yamato-shokugyou.example.jp/"
                                           :title "Yamato Shokugyou" :snippet "…yamato…" :score 12.5}
                                          {:url "https://yamato-shokugyou.example.jp/about"
                                           :title "About" :snippet "…about yamato…" :score 8.0}]}})
        result (ws/search! http sess "yamato")]
    (is (:ok result))
    (is (= 2 (:count result)))
    (is (= 40 (:candidates result)))
    (is (= "bafygraph" (:graph result)))
    (is (= 2 (count (:results result))))
    (is (= "https://yamato-shokugyou.example.jp/" (:url (first (:results result)))))
    (is (= 12.5 (:score (first (:results result)))))))

(deftest search!-passes-through-extra-result-fields-if-the-server-ever-adds-them
  (testing "ADR-2607192200's extracted-metadata addendum -- category/summary/entities
           aren't required by web.cljc's rank-pages today, but if/when they appear
           this client must not drop them"
    (let [http (fake-http (atom nil)
                          {:status 200
                           :body {:ok true :count 1
                                  :results [{:url "https://x/" :title "X" :snippet "s" :score 1.0
                                             :category "registry" :summary "a company" :entities ["X Corp"]}]}})
          result (ws/search! http sess "x")
          hit (first (:results result))]
      (is (:ok result))
      (is (= "registry" (:category hit)))
      (is (= "a company" (:summary hit)))
      (is (= ["X Corp"] (:entities hit))))))

(deftest search!-ok-false-body-is-not-ok
  (let [http (fake-http (atom nil) {:status 200 :body {:ok false :error "Bad Request"}})
        result (ws/search! http sess "x")]
    (is (not (:ok result)))
    (is (= {:ok false :error "Bad Request"} (:body result)))))

(deftest search!-non-2xx-is-not-ok
  (let [http (fake-http (atom nil) {:status 401 :body {:error "Unauthorized"}})
        result (ws/search! http sess "x")]
    (is (not (:ok result)))
    (is (= 401 (:status result)))))

(deftest search!-transport-error-is-not-ok-never-throws
  (let [http (fn [_] {:status nil :error "connection refused"})
        result (ws/search! http sess "x")]
    (is (not (:ok result)))
    (is (:error result))))

(deftest search!-http-fn-exception-never-throws
  (let [throwing (fn [_] (throw (ex-info "boom" {})))
        result (ws/search! throwing sess "x")]
    (is (not (:ok result)))
    (is (:error result))))

;; -- ->presence-signal ----------------------------------------------------

(deftest presence-signal-has-hits-true-with-top-hits-capped
  (let [results (mapv (fn [i] {:url (str "https://x/" i) :title (str "T" i) :snippet "s" :score (double i)})
                      (range 10))
        http (fake-http (atom nil) {:status 200 :body {:ok true :count 10 :results results}})
        signal (ws/->presence-signal http sess "x")]
    (is (true? (:has-hits? signal)))
    (is (= 10 (:hit-count signal)))
    (is (= 5 (count (:top-hits signal))) "default top-n is 5")
    (is (= :kotobase-search-index (get-in signal [:source :class])))))

(deftest presence-signal-has-hits-false-on-a-genuine-zero-match
  (let [http (fake-http (atom nil) {:status 200 :body {:ok true :count 0 :results []}})
        signal (ws/->presence-signal http sess "totally-unmatched-query-xyz")]
    (is (false? (:has-hits? signal)))
    (is (= 0 (:hit-count signal)))
    (is (= [] (:top-hits signal)))))

(deftest presence-signal-on-a-transport-failure-is-honestly-zero-not-a-guess
  (let [http (fn [_] {:status nil :error "down"})
        signal (ws/->presence-signal http sess "x")]
    (is (false? (:ok signal)))
    (is (false? (:has-hits? signal)))
    (is (= 0 (:hit-count signal)))))

(deftest presence-signal-shape-is-distinct-from-commoncrawl-enrichment-shape
  (testing "must never look like dossier.commoncrawl/->enrichment's shape -- see ns docstring"
    (let [http (fake-http (atom nil) {:status 200 :body {:ok true :count 1
                                                          :results [{:url "https://x/" :title "T" :snippet "s" :score 1.0}]}})
          signal (ws/->presence-signal http sess "x")]
      (is (not (contains? signal :domain)))
      (is (not (contains? signal :has-web-presence?)))
      (is (not (contains? signal :latest-capture-date)))
      (is (not (contains? signal :latest-capture-status)))
      (is (contains? signal :has-hits?))
      (is (contains? signal :hit-count))
      (is (contains? signal :top-hits)))))

(deftest presence-signal-top-n-is-overridable
  (let [results (mapv (fn [i] {:url (str "https://x/" i) :title (str "T" i) :snippet "s" :score (double i)})
                      (range 10))
        http (fake-http (atom nil) {:status 200 :body {:ok true :count 10 :results results}})
        signal (ws/->presence-signal http sess "x" {:top-n 2})]
    (is (= 2 (count (:top-hits signal))))))
