(ns dossier.policy
  "DisclosureGovernor — the independent compliance layer that earns the
  Dossier-LLM the right to commit or disclose. The LLM has no notion of
  scope boundaries, source provenance, contract entitlement or a data
  subject's right to dispute a record, so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD (write/disclose
  nothing) — this actor's analog of robotaxi's Minimal Risk Condition.

  Six checks, in priority order. The first three are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. scope-gate           — does the proposal touch a schema-excluded
                               (private-life) field?
    2. source-basis         — does every asserted fact/edge cite an allowed
                               source class (ADR-2607110400 §4)?
    3. licensed-disclosure  — is there an active, scoped contract, and does
                               the proposed column set stay within its tier?
    4. confidence floor     — LLM confidence below threshold → escalate.
    5. high-stakes gate     — subject is a government official or
                               sanctions/PEP-flagged → always escalate.
    6. correction requests  — a data-subject dispute NEVER auto-resolves,
                               at any confidence, any phase (ADR §1 check 6)."
  (:require [clojure.set :as set]
            [dossier.facts :as facts]
            [dossier.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def private-life-keys
  "Fields that must NEVER appear in a proposal's value/patch. There is no
  corresponding field in `dossier.store`'s schema at all — this check exists
  as defense in depth against an LLM (or a future schema change) smuggling
  one in, not as the primary control."
  #{:home-address :family :health :political-opinion :religion
    :sexual-orientation :realtime-location})

(def confidence-floor 0.6)

(def high-stakes
  "Subject-kind signals grave enough to always require a human, even when
  clean and high-confidence."
  #{:sanctions-flag :pep-flag :government-official-subject})

(def permissions
  "actor-role → set of operations it may perform."
  {:analyst             #{:record/upsert :relationship/draft}
   :compliance-officer  #{:record/upsert :relationship/draft :correction/request}
   :client              #{:disclosure/query :disclosure/screen-name
                          :disclosure/ownership-chain :disclosure/relationship-check}})

(def tier-columns
  "For `:disclosure/query`/`:disclosure/screen-name`/`:disclosure/ownership-
  chain`/`:disclosure/relationship-check` — the columns each licensed
  contract tier may see. Anything beyond this is over-disclosure
  (licensed-disclosure violation), the disclosure-minimization analog of
  `cloud-itonami-6310`'s `purpose-columns`. PEP/sanctions screening columns
  (`:hit?`/`:capacity`/`:org`) require at least `:tier/compliance` — a
  `:tier/basic` (registry-lookup) contract cannot run a name screen at all.
  Relationship-graph columns (`:owners`/`:has-sourced-ownership-data?`/
  `:related?`/`:kind`, ADR-2607110400 addendum 4) require `:tier/graph`,
  same as `:officials`/`:relationships` — even a `:tier/compliance` contract
  cannot run an ownership-chain or relationship-check query."
  (let [base #{:id :legal-name :jurisdiction :registration-no :status}
        screening #{:hit? :capacity :org}
        graph-only #{:owners :has-sourced-ownership-data? :related? :kind}]
    {:tier/basic      base
     :tier/compliance (into base (into #{:flags} screening))
     :tier/graph      (into base (into #{:flags :officials :relationships} (into screening graph-only)))}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- scope-violations [proposal]
  (let [ks  (set (keys (:value proposal)))
        bad (set/intersection ks private-life-keys)]
    (when (seq bad)
      [{:rule :scope-gate :detail (str "スキーマ外(私生活等)フィールドを含む: " (vec bad))}])))

(defn- source-basis-violations
  "Only `:record/upsert` and `:relationship/draft` assert new facts, so only
  those two ops are checked here. A missing source, or a `:class` outside
  `dossier.facts/allowed-source-classes`, is a HARD rejection regardless of
  the LLM's stated confidence."
  [{:keys [op]} proposal]
  (when (contains? #{:record/upsert :relationship/draft} op)
    (let [src (:source proposal)]
      (when (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :source-basis
          :detail (str "出典が無いか許可された出典クラスでない: " (pr-str src))}]))))

(defn- licensed-disclosure-violations
  "`:disclosure/query`/`:disclosure/screen-name`/`:disclosure/ownership-
  chain`/`:disclosure/relationship-check` are only ever served against a
  Store-registered, active contract — never against caller-asserted
  context. Over-disclosure (columns beyond the contract's tier — this also
  structurally excludes `:tier/basic` from screening and `:tier/compliance`
  from the two graph-only ops, since their columns aren't in those tiers'
  allowed sets at all) is checked the same pass."
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (contains? #{:disclosure/query :disclosure/screen-name
                     :disclosure/ownership-chain :disclosure/relationship-check} op)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn check
  "Censors a Dossier-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool :correction? bool}.

   - :hard?       — at least one HARD violation (scope/source-basis/
                    licensed-disclosure). Forces HOLD; a human cannot
                    override.
   - :escalate?   — soft: low confidence, high-stakes subject, OR a
                    correction request. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit/-disclose."
  [request context proposal st]
  (let [hard   (into []
                     (concat (rbac-violations request context)
                             (scope-violations proposal)
                             (source-basis-violations request proposal)
                             (licensed-disclosure-violations request context proposal st)))
        conf       (:confidence proposal 0.0)
        low?       (< conf confidence-floor)
        stakes?    (boolean (high-stakes (:stake proposal)))
        correction? (= :correction/request (:op request))
        hard?      (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?) (not correction?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes? correction?))
     :high-stakes? stakes?
     :correction?  correction?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
