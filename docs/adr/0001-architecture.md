# ADR-0001: cloud-itonami-isic-8291 — Dossier-LLM を封じ込めた知能ノードとする企業/コンプライアンス・インテリジェンス・アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-6310`(旧 `gftd-talent-actor`)ADR-0001(HR-LLM を
  PolicyGovernor で封じ込めるパターンの原型)、`cloud-itonami-M6910` ADR-0001
  (spec→実装昇格・R0正直スコープの先例)、robotaxi-actor ADR-0001(研究モデルを
  信頼境界に封じ込める actor 設計)、langgraph-clj ADR-0001(Pregel superstep +
  interrupt + Datomic checkpoint)
- 文脈: com-junkawasaki/root superproject ADR-2607110400(本 ADR の対、経緯・
  スコープ決定の全文はそちら)

## 課題

「全世界の企業・公人・政府・組織図・連絡先・人間関係を分析する actor」の要否
が問われ、既存 actor には該当するものが無いと判明した。オーナーから
「Palantir のように内部データとして保持し、cloud-itonami の産業SaaSごとに
情報を保持して契約したuserのみに開示する」という業態方針が示され、これは
D&B / Moody's Orbis(BvD) / Refinitiv World-Check と同型の
**credit-bureau/business-information-services**(ISIC 8291)であると確認した。

一方、企業登記の名寄せ・関係性推論・開示列の提案には LLM が有効だが、
**LLM に事実の書き込み・開示・訂正確定を直接行わせるのは危険**である
(出典なき関係性の断定=名誉毀損リスク、契約 tier を超えた開示、私生活データの
スコープ逸脱)。したがって設計課題は「LLM で企業インテリジェンスを回す」こと
ではなく、**「LLM を信頼境界の内側に封じ込め、スコープ・出典・開示ライセンス・
人間レビューの層をどう被せるか」**である。これは `cloud-itonami-6310` が
HR-LLM を PolicyGovernor で封じ込めた構図の、そのままの写像である。

## 決定

### 1. Dossier-LLM は最下層の1ノードに封じ込め、直接書き込み/開示/訂正確定させない

OperationActor 内で Dossier-LLM は *proposal*(登記アップサート案・関係性
ドラフト・開示列案・訂正解決案 ＋ 出典/根拠トレース)のみを返す**助言者**として
扱う。出力は必ず独立した `DisclosureGovernor` を通してから台帳に commit する。
**単一の不変条件**:

> **Dossier-LLM は、DisclosureGovernor が拒否する事実の書き込み・開示・
> 訂正確定を決して行わない。**

`cloud-itonami-6310` の「HR-LLM は PolicyGovernor が拒否する人事レコードの
書き込み・開示を決して行わない」と同型だが、対象を「書き込み・開示」の2つ
から「書き込み・**開示**・**訂正確定**」の3つに拡張した — この業態の最大の
リスク面が disclosure(誰に何を見せるか)であるため。

### 2. OperationActor = langgraph-clj StateGraph、1 run = 1 操作

```
intake → advise(Dossier-LLM) → govern(DisclosureGovernor) → decide ─┬─ ok・確信・低リスク ──────▶ commit → END
                                                                    ├─ 重大/低確信/訂正申立て ─▶ request-approval
                                                                    │                            [interrupt-before]
                                                                    │                            人間レビュアーが確認
                                                                    │                            resume ─▶ commit | hold
                                                                    └─ スコープ/出典/ライセンス違反 ─▶ hold → END
```

- 連続処理ループを持たず「1操作=1 run」とし、各操作を監査可能・checkpoint 可能
  にする。
- `:context` チャネルに **actor-role/tenant/phase を外部注入**。
- `interrupt-before #{:request-approval}` を実際の人間レビュー(コンプライアンス
  担当者・データ主体からの訂正申立ての確認)に転用。
- `:audit` チャネルに提案根拠・出典・判定・承認・却下を蓄積 → 監査台帳の証跡が
  同一ファクトログから落ちてくる。

### 3. DisclosureGovernor は Dossier-LLM と別系統、6チェック

| 責務 | 機構 |
|---|---|
| 権限分離 (RBAC) | actor-role が operation に権限を持つか |
| **scope-gate** | 提案が私生活/家族/思想信条/健康/性的指向/リアルタイム所在地フィールドを含まないか(スキーマ自体に存在しない) |
| **source-basis** | 事実/関係edgeが許可された出典クラスを引用しているか(無出典は確信度に関わらず拒否) |
| **licensed-disclosure** | 有効な契約(tenant×tier)があり、開示列がその tier 内か |
| 確信度フロア | LLM 信頼度が閾値未満 → 人間レビューへ escalate |
| **high-stakes gate** | 対象が制裁/PEPフラグ・政府職員 → 必ず人間レビュー |
| **correction-request** | データ主体の訂正申立ては確信度に関わらず常に人間レビュー、どの phase でも auto 化しない |

**scope-gate・source-basis・licensed-disclosure は hold 固定**(人間承認では
上書きできない)。soft(確信度/high-stakes/correction)だけが人間が可否を決める。

### 4. R0 の正直なスコープ(捏造禁止)

`cloud-itonami-M6910` の「10法域のみ spec-basis」に倣い、出典カタログ
(`src/dossier/facts.cljc`)は実在する6つの公開一次情報源のみ(日本 法人番号
公表サイト・UK Companies House・Germany Unternehmensregister・Estonia
e-Business Register・USA SEC EDGAR・EU consolidated sanctions list)。
`facts/coverage` が常に正直に現状を報告し、拡張は実在するソースの追記でのみ
行う(推論・未検証ソースの追加禁止)。Store は `MemStore` のみが既定
(`DatomicStore` は次のシームとして用意済み)。

### 5. スコープ境界はスキーマレベル(構造的)

私生活・家族・思想信条・健康・性的指向・リアルタイム所在地に対応する
フィールドは `dossier.store` のスキーマに一切存在しない。scope-gate の
runtime チェック(`private-life-keys`)は**二重の防御**であり、第一の防御は
「そもそもそのフィールドが無い」という構造そのもの。

## Consequences

- (+) `kotoba-lang/industry` registry の ISIC 8291 スロットが `:spec` から
  実装へ昇格した(`cloud-itonami-M6910` の 6910 に続く2件目の昇格実例)。
- (+) 「保持は自由・開示は契約者限定」という Palantir 型業態が、既存の
  「封じ込め+独立governor+不変台帳」actor パターンにそのまま収まった —
  新パターンの発明は不要だった。
- (+) 私生活データの除外がスキーマレベルであるため、実行時チェックの
  実装漏れに依存しない。
- (-) R0 の出典カバレッジは6ソースのみ。`facts/catalog` への追記でのみ拡大
  する(捏造禁止)。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。実運用の与信/
  制裁データベンダー統合は operator の責任範囲。
- (-) 175 blueprint fleet への `:corporate-intelligence` capability 配線は
  本 ADR のスコープ外(フォローアップ)。

## 代替案と不採用理由

- **私生活/人脈まで含む広いスコープ**: 実世界の D&B/Orbis/World-Check が
  適法に運用できているのはまさに「職務上の公開情報のみ」という境界による。
  広げると GDPR Art.9 プロファイリング規制・名誉毀損・ストーキング可能化の
  リスクが非対称に増大し、正当な SaaS として運用できなくなる。オーナー確認
  済みの narrow scope を採用。
- **Governor 名を PolicyGovernor のまま流用**: この業態の最大リスクは
  disclosure(誰に何を見せるか)であり、書き込みだけでなく開示・訂正確定も
  ゲートする必要があったため、意図を明確にする `DisclosureGovernor` に改名。
- **LLM に開示権限を直接付与(エージェント自律)**: 速いが、出典なき断定・
  契約超過開示・スコープ逸脱を構造的に防げない。単一不変条件(決定1)に反する。
