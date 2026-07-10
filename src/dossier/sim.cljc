(ns dossier.sim
  "Demo runner: push five representative operations through one
  OperationActor and watch the DisclosureGovernor + approval workflow earn
  the Dossier-LLM the right to commit or disclose.

    op1  役員登記アップサート(出典あり・正当)              → commit
    op2  関係性ドラフトが出典なし(ハルシネーション)        → source-basis REJECT → hold
    op3  開示クエリが tier/basic 契約なのに過剰列を要求    → licensed-disclosure REJECT → hold
    op3a 開示クエリが未契約 tenant から                    → licensed-disclosure REJECT → hold
    op4  開示クエリの対象が制裁フラグ付き(重大・高確信)    → 人間承認へ escalate → approve → commit
    op5  訂正申立て(どの phase でも常に人間レビュー)      → escalate → approve → commit
    op6  名前スクリーニング(cloud-itonami-isic-6910 等の  → 人間承認へ escalate → approve → commit
         KYC統合が実際に呼ぶクエリ形。制裁フラグ付き法人の
         officialをヒット)
    op7  所有関係チェーン照会(co-300 の所有者が制裁フラグ  → 人間承認へ escalate → approve → commit
         付きco-200と判明。cloud-itonami-isic-6420向け)
    op8  二者間関係照会(officialが制裁フラグ付き法人の役員 → 人間承認へ escalate → approve → commit
         を兼務。cloud-itonami-isic-6621/6622の利益相反
         チェック向け)

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [dossier.store :as store]
            [dossier.operation :as op]
            [dossier.facts :as facts]
            [dossier.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one operation on its own thread-id. If it interrupts for human
  approval, a compliance officer 'approves' and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "compliance-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        analyst {:actor-id "an-1" :actor-role :analyst}
        officer {:actor-id "co-1" :actor-role :compliance-officer}]

    (line "── R0 出典カバレッジ(正直な現状、'全世界' の一部のみ) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (Dossier-LLM sealed; DisclosureGovernor active) ──")

    (line "\nop1  役員登記アップサート(出典あり・正当)")
    (run-op! actor "op1"
             {:op :record/upsert :subject "of-4" :entity-kind :official
              :patch {:id "of-4" :name "佐藤 三郎(デモ)" :title "監査役" :org "co-100"
                      :capacity :officer
                      :source {:class :official-registry :ref "houjin-bangou:demo"}}}
             analyst true)

    (line "\nop2  関係性ドラフト — Dossier-LLM が出典なしで JV 関係を提案(ハルシネーション)")
    (run-op! actor "op2"
             {:op :relationship/draft :subject "rel-1" :from "co-100" :to "co-200"
              :kind :joint-venture :pct nil :source nil :as-of "2026-01-01" :bias? true}
             analyst true)

    (line "\nop3  開示クエリ(tier/basic 契約なのに officials/relationships/flags まで要求)")
    (run-op! actor "op3"
             {:op :disclosure/query :subject "co-100" :company-id "co-100" :greedy? true}
             {:actor-id "cl-1" :actor-role :client :tenant "tenant-basic"} true)

    (line "\nop3a 開示クエリ(登録されていない tenant から)")
    (run-op! actor "op3a"
             {:op :disclosure/query :subject "co-100" :company-id "co-100"}
             {:actor-id "cl-2" :actor-role :client :tenant "tenant-ghost"} true)

    (line "\nop4  開示クエリの対象が制裁フラグ付き法人(tier/compliance 契約・高確信でも人間承認)")
    (run-op! actor "op4"
             {:op :disclosure/query :subject "co-200" :company-id "co-200"}
             {:actor-id "cl-3" :actor-role :client :tenant "tenant-acme"} true)

    (line "\nop5  訂正申立て — データ主体が登記ステータスに異議(どの phase でも常に人間レビュー)")
    (run-op! actor "op5"
             {:op :correction/request :subject "co-100" :entity-kind :company
              :disputed-field :status :claim :inactive}
             officer true)

    (line "\nop6  名前スクリーニング(KYC統合が呼ぶクエリ形。制裁フラグ付き法人のofficialを検索)")
    (run-op! actor "op6"
             {:op :disclosure/screen-name :subject "tenant-acme" :name "Jane Smith (demo)"}
             {:actor-id "cl-3" :actor-role :client :tenant "tenant-acme"} true)

    (line "\nop7  所有関係チェーン照会(co-300 は制裁フラグ付きco-200に60%所有されている)")
    (run-op! actor "op7"
             {:op :disclosure/ownership-chain :subject "co-300" :company-id "co-300"}
             {:actor-id "cl-4" :actor-role :client :tenant "tenant-graph"} true)

    (line "\nop8  二者間関係照会(山田一郎は co-100 の役員だが、co-200 の役員も兼務)")
    (run-op! actor "op8"
             {:op :disclosure/relationship-check :subject "of-1"
              :person-name "山田 一郎(デモ)" :company-id "co-200"}
             {:actor-id "cl-4" :actor-role :client :tenant "tenant-graph"} true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-profile db "co-100" [:id :legal-name :jurisdiction :status])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの契約/出典で開示/書込したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
