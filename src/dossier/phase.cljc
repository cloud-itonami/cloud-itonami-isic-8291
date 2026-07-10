(ns dossier.phase
  "Phase 0→3 staged rollout — this actor's analog of robotaxi's ODD phases
  and `cloud-itonami-6310`'s HR rollout: start narrow (read-only), widen as
  trust grows. Where the DisclosureGovernor answers 'is this allowed?', the
  phase answers 'how much autonomy does the actor have *yet*?'. It can only
  ever make the actor MORE conservative than the governor: it downgrades a
  governor-clean commit to approval or hold, never the reverse.

    Phase 0  read-only        — no writes at all. `:disclosure/query` only
                                (still governor-gated).
    Phase 1  assisted-intake  — `:record/upsert` allowed, every write needs
                                human approval.
    Phase 2  + relationships  — adds `:relationship/draft` and
                                `:correction/request` (still approval-only).
    Phase 3  supervised auto  — governor-clean, high-confidence
                                `:record/upsert`/`:relationship/draft` may
                                auto-commit.

  `:correction/request` is deliberately NEVER a member of any phase's `:auto`
  set, at any phase — a data-subject dispute always reaches a human,
  independent of the DisclosureGovernor's own always-escalate check on the
  same op (ADR-2607110400's actuation invariant, the same shape as
  `cloud-itonami-M6910`'s `:filing/submit` never being auto at any phase).

  `gate` runs AFTER `policy/check`, taking the governor disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted disposition
  plus a reason when the phase changed it.")

(def read-ops  #{:disclosure/query :disclosure/screen-name})
(def write-ops #{:record/upsert :relationship/draft :correction/request})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}. `:correction/request` is intentionally
  absent from every phase's `:auto` set."
  {0 {:label "read-only"            :writes #{}
                                     :auto #{}}
   1 {:label "assisted-intake"      :writes #{:record/upsert}
                                     :auto #{}}
   2 {:label "assisted-relationship" :writes #{:record/upsert :relationship/draft :correction/request}
                                     :auto #{}}
   3 {:label "supervised-auto"      :writes #{:record/upsert :relationship/draft :correction/request}
                                     :auto #{:record/upsert :relationship/draft}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads (`:disclosure/query`) pass through unchanged (phase restricts
    write autonomy, not governed reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase → HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible → ESCALATE (:phase-approval),
    even if the governor was clean. `:correction/request` is never
    auto-eligible at any phase, so it always lands here once phase ≥ 2."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a DisclosureGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
