# Dossier Actor Design — Dossier-LLM as a contained intelligence node

D&B / Moody's Orbis(BvD) / Refinitiv World-Check 級の企業・コンプライアンス
インテリジェンスを、Palantir 型「内部保持・契約者限定開示」の運用で、SaaS課金
に依存せず OSS の actor として自前運用するための設計。`cloud-itonami-6310`
(旧 `gftd-talent-actor`) が HR-LLM を PolicyGovernor で封じ込めた構図を、
企業・役職者(職務上)・関係性インテリジェンスのドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか、そしてなぜスコープを絞るのか

企業登記の名寄せ・関係性(株主/役員/JV)の推論・開示列の提案は LLM で加速できる。
しかし LLM は次の理由で**開示・書き込み・訂正確定の最終権限を持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| 出典なしに関係性を「推論」で断定 | 名誉毀損・誤った企業関連付け |
| 私生活・家族・思想信条フィールドを紛れ込ませる | プロファイリング規制違反・スコープ逸脱 |
| 契約 tier を超えた列を開示 | 過剰開示・契約違反 |
| 政府職員/制裁対象への言及を高確信のまま自動処理 | 政治的リスク・誤情報の拡散 |

したがって設計課題は「LLM で企業インテリジェンスを回す」ことではなく、
**「LLM を信頼境界の内側に封じ込め、スコープ・出典・開示ライセンス・
人間レビューの層をどう被せるか」**である。オーナーの確認により、対象は
**職務上の公開情報のみ**(法人登記・役員/取締役/UBO(公開分)・政府機関の
職位/役職者・公表済み取引関係・法人としての連絡先)に絞られ、私生活・家族・
思想信条・所在地追跡・SNS推論は**スキーマにフィールドごと存在しない**。この
境界が D&B/Moody's Orbis/World-Check という実在業態の適法性の根拠そのもの。

## 2. アクター・トポロジ(監督ツリー)

```
DossierSystem (root supervisor)
│
├── RegistryActor ……… 法人/役職者/政府機関の登記事実(:record/upsert)
├── GraphActor ……… 関係性(株主/役員/JV/監督)の投影(relationship-graph-text)
│
├── OperationActor[op] … ★ 1操作 = 1 actor run; Dossier-LLM 封じ込め ★
│     ├── Dossier-LLM (sealed)   proposal only(src/dossier/llm.cljc)
│     ├── DisclosureGovernor     INDEPENDENT ゲート(src/dossier/policy.cljc)
│     ├── Committer              SSoT/台帳への書き込み(src/dossier/store.cljc)
│     └── Recorder                監査台帳(append-only)
│
├── ReviewActor ……… 人間レビュー(高リスク開示・訂正申立ての interrupt を受ける)
└── DisclosureActor ……… governed read(report.cljc、契約 tier 列のみ)
```

原則:

1. **Dossier-LLM は最下層ノードで、台帳・開示経路に直接触れない。** 出力は
   常に DisclosureGovernor で検閲される。
2. **監督。** 子の失敗は親へ escalate し、最終的に **hold(書き込み/開示しない)**
   に倒す。robotaxi の MRC(安全停止)に相当する既定。
3. **すべてが台帳に積まれる。** 「誰に・何を・どの契約/出典で開示したか」は
   監査台帳への Datalog クエリ — 監査・コンプライアンス・データ主体の
   訂正申立てが同一ファクトログから出る。

## 3. OperationActor 内部(Dossier-LLM ラッパー)

`src/dossier/operation.cljc` の langgraph-clj StateGraph として実装。
**1 run = 1 操作** — 有界で監査可能、無限内部ループを持たない。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

チャネル: `:request :context :proposal :verdict :disposition :record :approval :audit`

- **`:context` は外部注入**(`{:actor-id .. :actor-role .. :tenant .. :phase ..}`)。
  Dossier-LLM はこれを持たない。
- **`:govern` は Dossier-LLM と別系統**(スコープ表 + 出典クラス表 + 契約 tier 表)。
  LLM 提案を*拒否*して hold に substitute できる。
- **`interrupt-before #{:request-approval}`** で実際の人間レビューへ。
  レビュアーは resume 時に `{:approval {:status :approved}}` を注入する。

### 3.1 注入される3つの依存(すべて swap)

- **Store**(`dossier.store/Store` プロトコル): `MemStore`(既定)/ `DatomicStore`
  (`langchain.db` = Datomic-API 互換 EAV)。両者は同一契約テストで等価性を保証。
- **Advisor**(`dossier.llm/Advisor` プロトコル): `mock-advisor`(既定)/
  `llm-advisor`(`langchain.model` の ChatModel)。応答破損時は confidence 0
  の noop に落ち、LLM 不調が auto-commit/開示にならない。
- **Phase**(`dossier.phase`、context の `:phase 0..3`): 段階導入。read-only →
  assisted → supervised-auto。governor より保守的にしか働かない。
  **`:correction/request` はどの phase の `:auto` にも入らない**(恒久ゲート)。

## 4. DisclosureGovernor(独立検閲層)

`src/dossier/policy.cljc`。LLM とは別経路で、提案を可決/拒否/escalate に判定する。

```clojure
(policy/check request context proposal store)
;; => {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool :correction? bool}
```

判定の優先順位(上が強い、HARD は人間承認でも上書き不可):

1. **RBAC** — `permissions` 表で `actor-role × operation` を引く。
2. **scope-gate** — 提案の value が私生活系フィールド(`private-life-keys`)を
   含んだら HARD violation。スキーマ自体にこれらのフィールドは無い。
3. **source-basis** — `:record/upsert`/`:relationship/draft` の `:source` が
   `dossier.facts/allowed-source-classes` に無ければ HARD violation
   (確信度に関わらず拒否)。
4. **licensed-disclosure** — `:disclosure/query` は Store 登録済みの有効な
   契約(tenant×tier)を要求し、提案列が契約 tier を超えたら HARD violation。
5. **確信度フロア** — `:confidence < 0.6` → escalate(soft)。
6. **high-stakes gate** — 対象が制裁/PEPフラグ・政府職員 → 必ず人間承認(soft)。
7. **correction-request** — `:correction/request` は常に escalate(soft だが
   confidence に関わらず無条件)。

## 5. SSoT と監査台帳

`src/dossier/store.cljc`。dev は in-mem の EDN 事実層(本番は Datomic)。

- **entities**: `companies` `officials`(capacity=officer|director|ubo|
  government-official) `agencies` `relationships`(edge) `contracts`(licensing)。
- **commit-record!**: 操作結果を SSoT に反映(`:disclosure-serve` は SSoT
  変更なし — 台帳のみ)。
- **append-ledger!**: 全 commit/reject/開示を**不変台帳**に積む。

「誰に・何を・どの契約/出典で開示したか」を台帳の述語で問えることが、この
業態の regulatory な監査要件(FCRA型 dispute 権含む)そのもの。

## 6. 開示(governed read)

`src/dossier/report.cljc`。`render-profile` は DisclosureGovernor が承認した
列のみを出力、`relationship-graph-text` は関係性が承認された時のみ組織図
スタイルで投影する。列ポリシーはコードで固定される。

## 7. デモ(`clojure -M:dev:run`)

`src/dossier/sim.cljc` が5操作を actor に通す(§sim.cljc docstring 参照):
正当な登記アップサート → commit、出典なし関係性ドラフト → hold、
tier超過/未契約の開示 → hold、制裁フラグ対象への開示 → 人間承認 → commit、
訂正申立て → 常に人間承認 → commit。

## 8. テスト(`clojure -M:dev:test`)

`test/dossier/policy_contract_test.clj` が**ガバナンス契約を実行可能**にする。
`test/dossier/phase_test.clj` が段階導入と「訂正は恒久的に人間専用」を保証。
`test/dossier/facts_test.clj` が出典カタログ自体の正直さ(捏造禁止)を保証。

## 9. 実装と業態の対応(D&B/Orbis/World-Check → dossier actor)

| 実在業態の機能 | dossier actor での実体 |
|---|---|
| 企業登記データベース | `store` companies + `:record/upsert` |
| 役員/取締役/UBO | `store` officials(capacity 別) |
| 企業関係性グラフ(株主/JV) | `store` relationships + `report/relationship-graph-text` |
| PEP/制裁スクリーニング | `official.capacity=:government-official` / `company.flags.sanctions?` + high-stakes gate |
| 与信/コンプライアンスレポート | `report/render-profile`(tier 列限定の governed read) |
| データ主体の異議申立て(FCRA dispute) | `:correction/request`(恒久 human-only) |
| アクセス権限・契約 | DisclosureGovernor RBAC 表 + `contracts` |
| (SaaS/従来ベンダーと同型)監査台帳 | `store` append-only ledger |
| データ主権 | SSoT = 自分の Datomic |

## 10. 所有関係チェーン・二者間関係照会(ADR-2607110400 addendum 4)

`:disclosure/query`/`:disclosure/screen-name` は「この法人/この人物は何か」
を answer するが、`cloud-itonami-isic-6420`(持株会社の実質的支配者確認)・
`cloud-itonami-isic-6621`/`6622`(査定人/仲介人の利益相反確認)は「この
関係は存在するか」を answer する必要があり、別の2つの governed read op を
追加した:

- **`:disclosure/ownership-chain`**(`{:company-id|:company-name ..}` →
  `{:owners [{:owner-id :pct :source :as-of} ..] :has-sourced-ownership-data?
  bool}`)— 対象法人へ向かう `:ownership` kind の relationship edge を
  1 hop だけ辿る。`:has-sourced-ownership-data? false` は「所有者がいない」
  ではなく「8291に出典データが無い」——それ自体を清潔判定として扱わない。
- **`:disclosure/relationship-check`**(`{:person-name .. :company-id|
  :company-name ..}` → `{:related? bool :kind :org-membership|<edge-kind>|nil}`)
  — 名前一致した official の `:org` 一致、または当該人物からの relationship
  edge(1 hop のみ)のどちらかで判定。

両方とも `:tier/graph` 以上を要求(`dossier.policy/tier-columns` の
`graph-only` 列)。`stake` は所有者/関係先が制裁フラグ・政府職員の場合のみ
`:sanctions-flag`(関係が存在すること自体はhigh-stakesではない、フラグ付き
相手との関係である場合のみ)。`cloud-itonami-isic-6420`/`6621`/`6622` への
実配線はフォローアップ(未実施、ADR addendum 4 参照)。
