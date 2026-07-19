(ns dossier.llm
  "Dossier-LLM client — the *contained intelligence node*.

  It normalizes registry-upsert patches, drafts relationship edges between
  entities, proposes disclosure column sets for a licensed company query or
  a licensed PEP/sanctions name screen, and drafts correction resolutions.
  CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields/source it cited), never
  a committed or disclosed record. Every output is censored downstream by
  `dossier.policy` (the DisclosureGovernor) before anything touches the SSoT
  or leaves the actor.

  Like `cloud-itonami-6310`'s HR-LLM, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised end-to-end.
  In production this calls a real LLM (kotoba-llm) with the same proposal
  shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the source-basis gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used
     :source     {:class kw :ref str}|nil ; citation — SCANNED by source-basis
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map|nil        ; the record patch/edge, for upsert/relationship
     :columns    [kw ..]|nil    ; proposed disclosure column set
     :stake      kw|nil         ; :sanctions-flag/:government-official-subject/nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [dossier.store :as store]))

(defn- flagged?
  "True when `id` (a company or official id) is demo-flagged sanctions/PEP or
  is a government official — the high-stakes signal the DisclosureGovernor
  always escalates, regardless of confidence."
  [db id]
  (boolean (or (get-in (store/company db id) [:flags :sanctions?])
               (= :government-official (:capacity (store/official db id))))))

(defn- propose-upsert
  "Registry upsert — the LLM only normalizes/validates the patch (adds no
  new facts). `:leaky?` injects the failure mode we must defend against: a
  private-life field (home address) sneaking into the patch — the
  DisclosureGovernor's scope-gate must reject this outright."
  [_db {:keys [entity-kind patch leaky?]}]
  (let [effect (case entity-kind
                 :company :upsert-company :official :upsert-official
                 :agency :upsert-agency)
        patch* (if leaky? (assoc patch :home-address "デモ住所1丁目(スキーマ外)") patch)]
    {:summary   (str (name entity-kind) " レコード更新: " (:id patch*))
     :rationale "出典引用済みの登記/届出事実の正規化のみ。新規事実の生成なし。"
     :cites     (vec (keys (dissoc patch* :source)))
     :source    (:source patch*)
     :effect    effect
     :value     patch*
     :stake     nil
     :confidence 0.95}))

(defn- propose-relationship-draft
  "Relationship-edge draft from source documents. `:bias?` injects the
  failure mode this actor exists to catch: the LLM proposing an edge with NO
  citation ('pattern-matched' instead of sourced) — the DisclosureGovernor's
  source-basis gate must reject this regardless of how confident the LLM is."
  [db {:keys [from to kind pct source as-of bias?]}]
  (let [src (when-not bias? source)]
    {:summary   (str from " → " to " (" (name kind) ") の関係を検出")
     :rationale (if bias?
                  "文書間の共通役員パターンからの推論(出典なし)。"
                  (str "出典: " (pr-str source)))
     :cites     (if bias? [] [:source])
     :source    src
     :effect    :add-relationship
     :value     {:id (str from "-" to "-" (name kind))
                 :from from :to to :kind kind :pct pct :source src :as-of as-of}
     :stake     (when (or (flagged? db from) (flagged? db to)) :sanctions-flag)
     ;; deliberately HIGH confidence even when bias? — proves the hard
     ;; source-basis gate does not care about confidence at all.
     :confidence (if bias? 0.9 0.85)}))

(defn- propose-disclosure
  "Disclosure column-set proposal for a licensed query. Resolves by
  `:company-id` or, since a consumer may only have a company's NAME on
  hand (e.g. a correspondent bank's `member-name`, a brokerage account's
  `:client`, an audit engagement's client company name), `:company-name`
  via `dossier.store/company-by-name` — the same resolution pattern
  `propose-ownership-chain`/`propose-relationship-check` already use.
  `:greedy?` injects over-disclosure (pulls relationship/officials/flags
  columns beyond a basic-tier contract) — the DisclosureGovernor's
  licensed-disclosure gate must reject the excess columns."
  [db {:keys [company-id company-name greedy?]}]
  (let [c (or (and company-id (store/company db company-id))
              (and company-name (store/company-by-name db company-name)))
        cid (:id c)
        base [:id :legal-name :jurisdiction :registration-no :status]
        greedy-extra [:officials :relationships :flags]]
    {:summary   (str "開示列提案: " (or company-id company-name))
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :value     {:company-id cid :flags (or (:flags c) {})}
     :stake     (when (get-in c [:flags :sanctions?]) :sanctions-flag)
     :confidence 0.9}))

(defn- propose-name-screen
  "PEP/sanctions name-screening proposal for a licensed KYC query — the
  actual query shape a consuming actor (e.g. cloud-itonami-isic-6910's
  officer screening) needs: search by NAME, not by company id. Exact-match
  only in R0 (`dossier.store/official-by-name`); no fuzzy/phonetic matching.
  Result columns (`:hit?` `:capacity` `:org`) require at least
  `:tier/compliance` — `dossier.policy/tier-columns` gates this the same way
  as `propose-disclosure`.

  A NOT-FOUND result is high confidence, not low: 'no match against our R0
  source catalog' is itself a definitive, actionable screening result (the
  same way a real PEP/sanctions screening product returns a confident
  negative), not an inference the LLM is unsure about. The catalog's
  narrowness is a documented COVERAGE characteristic (`dossier.facts/
  coverage`), not a per-query confidence hedge — conflating the two would
  make every screen of someone genuinely outside R0's scope escalate for
  human review, defeating the point of an automatable screening op."
  [db {:keys [name]}]
  (let [off (store/official-by-name db name)
        hit? (boolean (and off (or (= :government-official (:capacity off))
                                    (get-in (store/company db (:org off)) [:flags :sanctions?]))))]
    {:summary   (str "名前スクリーニング: " name)
     :rationale (if off
                  "登録済み official レコードとの完全一致。capacity/所属法人の flags を照合。"
                  "R0出典カタログ内に完全一致なし(範囲内での確定的な非該当。カバレッジの狭さは facts/coverage 側の特性であり、この判定自体の確信度を下げる理由ではない)。")
     :cites     [:official-by-name]
     :source    nil
     :effect    :disclosure-serve
     :columns   [:hit? :capacity :org]
     :value     {:found? (boolean off) :hit? hit? :capacity (:capacity off) :org (:org off)}
     :stake     (when hit? :sanctions-flag)
     :confidence (if off 0.9 0.85)}))

(defn- propose-ownership-chain
  "Beneficial-ownership-chain proposal for a licensed query (ADR-2607110400
  addendum 4): direct `:ownership`-kind relationship edges pointing AT the
  target company — resolved by `:company-id` or, since a consuming actor
  (e.g. a holding-company actor tracking a subsidiary only by name) may not
  have an 8291 id on hand, by `:company-name` via `dossier.store/company-
  by-name`. Answers 'who owns this entity, per our sourced relationship
  data' — distinct from `propose-disclosure`'s company-profile shape, which
  answers 'what are this entity's own registry facts'. One hop only (R0
  does not walk multi-hop ownership chains). Result columns (`:owners`
  `:has-sourced-ownership-data?`) require `:tier/graph`, same as
  `:officials`/`:relationships` elsewhere."
  [db {:keys [company-id company-name]}]
  (let [c (or (and company-id (store/company db company-id))
              (and company-name (store/company-by-name db company-name)))
        cid (:id c)
        owners (when cid (filter #(= cid (:to %)) (store/relationships-of db cid)))]
    {:summary   (str "所有関係チェーン照会: " (or company-id company-name))
     :rationale (cond
                  (nil? c) "対象法人が見つかりません(未収載)。"
                  (seq owners) "登録済み ownership edge との一致。"
                  :else "対象法人は見つかったが、登録済み ownership edge が無い(未収載 ≠ 所有者なし)。")
     :cites     [:relationships-of]
     :source    nil
     :effect    :disclosure-serve
     :columns   [:owners :has-sourced-ownership-data?]
     :value     {:company-id cid
                 :has-sourced-ownership-data? (boolean (seq owners))
                 :owners (mapv (fn [e] {:owner-id (:from e) :pct (:pct e)
                                        :source (:source e) :as-of (:as-of e)})
                               owners)}
     :stake     (when (some #(flagged? db (:from %)) owners) :sanctions-flag)
     :confidence (if (nil? c) 0.85 0.9)}))

(defn- propose-relationship-check
  "Two-party relationship proposal for a licensed conflict-of-interest query
  (ADR-2607110400 addendum 4): does the NAMED person (`:person-name`,
  exact-match via `official-by-name`) have a professional-capacity
  relationship with a target — a company (`:company-id`/`:company-name`,
  matched by serving as an official AT that entity or a direct edge) OR
  ANOTHER named person (`:target-person-name`, matched by a direct edge
  between the two officials — `relationships-of` is entity-kind-agnostic,
  so this is the same edge lookup, just resolving the target id via
  `official-by-name` instead of `company`/`company-by-name`).

  A caller that does not itself know whether its counterparty is a company
  or a person (e.g. cloud-itonami-isic-6621/6622's `party` records store
  both insurers/customers AND individual adjusters/brokers/claimants under
  the same generic shape) may instead pass `:target-name` alone: it tries
  `company-by-name` first, then `official-by-name` — the caller does not
  have to guess or make two round-trip calls.

  One hop only (R0 does not walk multi-hop chains, and does not infer a
  relationship from a shared employer/owner alone). Result columns
  (`:related?` `:kind`) require `:tier/graph`."
  [db {:keys [person-name company-id company-name target-person-name target-name]}]
  (let [p (store/official-by-name db person-name)
        target (or (and company-id (store/company db company-id))
                   (and company-name (store/company-by-name db company-name))
                   (and target-person-name (store/official-by-name db target-person-name))
                   (and target-name (or (store/company-by-name db target-name)
                                        (store/official-by-name db target-name))))
        cid (:id target)
        org-match? (and p cid (= cid (:org p)))
        edge (when (and p cid)
               (some #(when (or (= cid (:to %)) (= cid (:from %))) %)
                     (store/relationships-of db (:id p))))
        related? (boolean (or org-match? edge))]
    {:summary   (str "関係性照会: " person-name " × " (or company-id company-name target-person-name target-name))
     :rationale (cond
                  (nil? p) "対象人物が見つかりません(未収載)。"
                  (nil? target) "対象(法人または人物)が見つかりません(未収載)。"
                  related? "職務上の関係(所属または関係edge)が一致。"
                  :else "登録済みデータの範囲内で関係性なし。")
     :cites     [:official-by-name :relationships-of]
     :source    nil
     :effect    :disclosure-serve
     :columns   [:related? :kind]
     :value     {:found? (boolean (and p target)) :related? related?
                 :kind (cond org-match? :org-membership edge (:kind edge) :else nil)}
     :stake     (when (and related? (or (and cid (flagged? db cid))
                                         (and p (flagged? db (:id p)))))
                  :sanctions-flag)
     :confidence (if (and p target) 0.9 0.85)}))

(defn- propose-correction
  "Correction/dispute resolution draft. The LLM may draft a proposed
  resolution but this NEVER auto-applies — `dossier.policy` and
  `dossier.phase` both structurally force every `:correction/request` to
  human review (ADR-2607110400 §1, check #6), independent of confidence."
  [_db {:keys [entity-kind target-id disputed-field claim]}]
  {:summary   (str target-id " の " disputed-field " について訂正申立てへの解決案ドラフト")
   :rationale (str "申立て内容: " claim "。出典による裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :correction-apply
   :value     {:kind (case entity-kind
                       :company :companies :official :officials :agency :agencies)
               :patch {disputed-field claim}}
   :stake     :correction-request
   :confidence 0.5})

;; Forward declaration: `propose-discover-candidates` is defined further
;; below (near `mock-advisor`/`llm-advisor`) but `infer` (immediately
;; below) already needs to reference it in its `case` dispatch. Without
;; this, the whole ns fails to COMPILE (not just at runtime) — Clojure
;; resolves symbols at compile time, in file order, and has no forward
;; visibility into a `defn-` further down the same file.
(declare propose-discover-candidates)

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :record/upsert       (propose-upsert db request)
    :relationship/draft  (propose-relationship-draft db request)
    :disclosure/query    (propose-disclosure db request)
    :disclosure/screen-name (propose-name-screen db request)
    :disclosure/ownership-chain (propose-ownership-chain db request)
    :disclosure/relationship-check (propose-relationship-check db request)
    :disclosure/discover-candidates (propose-discover-candidates db request)
    :correction/request  (propose-correction db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :stake nil :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a real
;; LLM in production. Either way its output is a PROPOSAL the
;; DisclosureGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは企業・法人・役職者(職務上のみ)・関係性の情報アドバイザーです。"
       "与えられた事実のみに基づき、提案を1つだけ EDN マップで返します。"
       "説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :source({:class .. :ref ..}か nil) "
       ":effect(:upsert-company|:upsert-official|:upsert-agency|:add-relationship|"
       ":disclosure-serve|:correction-apply) :value(該当マップ) "
       ":stake(:sanctions-flag 等/無ければ nil) :confidence(0..1)。\n"
       "重要: 私生活・家族・思想信条・健康・性的指向・リアルタイム所在地に関する情報は"
       "一切扱ってはいけません(スキーマにそのフィールドは存在しません)。"
       "出典(:source)を伴わない事実・関係性は絶対に提案してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :relationship/draft {:from (:from op) :to (:to op)}
    :disclosure/query   {:company (store/company st subject)}
    {:entity (or (store/company st subject) (store/official st subject) (store/agency st subject))}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the DisclosureGovernor escalates/holds — an
  LLM hiccup can never auto-commit or auto-disclose."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba), or
  `model/mock-model` for offline tests. `gen-opts` is forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn- propose-discover-candidates
  "Candidate-discovery proposal for LEI/ToS collection rollout.
  Input: {:vertical {:isic \"0610\" | :isco \"...\" | :country \"...\" | ...} :count 3}
  Output: top-N real global/listed companies for that vertical + reason citations.

  The LLM proposal ONLY narrows the candidate list via source citations.
  It never fabricates companies. The actual registry lookup (GLEIF/SEC/Companies House)
  happens downstream in the deterministic registry clients, which are NOT governed
  (per ADR-2607150100: registry fetches bypass the governor)."
  [_db {:keys [vertical count] :or {count 3}}]
  (let [code-key (first (keys vertical))
        code-val (get vertical code-key)
        candidates-demo (case [code-key code-val]
                          [:isic "0610"] [{:legal-name "ExxonMobil Corporation" :ticker "XOM" :source "S&P 500 crude-extraction"}
                                         {:legal-name "Chevron Corporation" :ticker "CVX" :source "S&P 500 crude-extraction"}
                                         {:legal-name "Shell plc" :ticker "SHELL" :source "FTSE 100 crude-extraction"}]
                          [:country "USA"] [{:legal-name "Apple Inc." :ticker "AAPL" :source "NYSE top-market-cap"}
                                          {:legal-name "Microsoft Corporation" :ticker "MSFT" :source "NASDAQ top-market-cap"}
                                          {:legal-name "Alphabet Inc." :ticker "GOOGL" :source "NASDAQ top-market-cap"}]
                          [])
        candidates (take count candidates-demo)]
    {:summary   (str "候補企業発見: " (pr-str vertical) " × " count)
     :rationale "公式出典(世界銀行統計/業界レポート/上場企業リスト)による実在top企業リスト。LLMは捏造しない。"
     :cites     [code-key]
     :source    {:class :reference-catalog :ref "industry-taxonomy-v1"}
     :effect    :discover-candidates
     :value     {:vertical vertical :candidates (vec candidates)}
     :confidence 0.95}))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (dispute appeals, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :dossierllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
