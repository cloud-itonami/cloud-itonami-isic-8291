# Open Business Blueprint: cloud-itonami-isic-8291

This repository publishes an OSS business model for operating a
corporate/compliance intelligence service (D&B / Moody's Orbis(BvD) /
Refinitiv World-Check class) on itonami.cloud, with a Palantir-style
hold-internally / disclose-only-to-licensed-contracts operating model.

## Classification

- Repository name: `cloud-itonami-isic-8291`
- Primary classification: ISIC Rev.4 8291
- Activity: activities of collection agencies and credit bureaus
- Served domain: corporate registry, officer/director/UBO, government-agency
  and relationship-graph intelligence, scoped to professional/official-
  capacity public-record facts only

The ISIC code describes the business activity of collecting and
disseminating creditworthiness/standing information about enterprises. The
dossier actor is the first productized service inside that classification in
this fleet, and is the second `:spec → real repo` promotion in
`kotoba-lang/industry`'s registry (after `cloud-itonami-M6910`'s 6910).

## Customer

Primary customers (contracted, licensed access only — never public/anonymous):

- vendor-due-diligence / KYC-adjacent compliance teams
- supply-chain and counterparty risk teams
- other `cloud-itonami-{ISIC}` blueprint operators who need corporate/
  official-capacity data as a licensed capability rather than building it
  themselves (the `:corporate-intelligence` wholesale pattern, ADR-2607110400 §5)
- researchers and journalists needing sourced, professional-capacity-only
  corporate/government relationship data

## Problem

Corporate/compliance intelligence vendors (D&B, Moody's Orbis, World-Check,
LexisNexis) hold this data inside closed systems and charge continuously for
access. Customers cannot inspect the governance logic (why was this fact
disclosed, what source backs this relationship claim), and vendors have no
structural guarantee against scope creep into private-life profiling.

## Offer

Operators provide an OSS actor for corporate/compliance intelligence:

- company registry facts (jurisdiction, registration number, status)
- officers/directors/UBO in their professional capacity only
- government-agency and government-official (in role) records
- relationship graph (ownership, directorship, JV, regulatory oversight,
  business contact) — every edge source-cited
- sanctions/PEP flagging sourced from public government lists
- governed, tier-scoped disclosure (never a public/anonymous query surface)
- an FCRA-style correction/dispute channel, always human-reviewed
- immutable audit ledger of every disclosure event

The core promise: Dossier-LLM can draft entity resolution and relationship
proposals, but it cannot commit, disclose, or resolve a dispute unless the
independent DisclosureGovernor allows it.

## Revenue

Operators can sell:

- per-seat or per-query licensed access (contract tenant × tier)
- tiered subscriptions: `:tier/basic` (registry facts) → `:tier/compliance`
  (+ sanctions/PEP flags) → `:tier/graph` (+ officials/relationships)
- wholesale API access to other `cloud-itonami-{ISIC}` blueprint operators
  (the `:corporate-intelligence` capability pattern)
- managed hosting: monthly subscription per tenant
- data-source integration: connecting a real registry/sanctions feed
- compliance package: audit export, dispute-handling SLA, security review

| Package | Customer | Price shape |
|---|---|---|
| Basic registry lookup | small compliance team | per-query or low monthly tier |
| Compliance tier | vendor-due-diligence / KYC-adjacent team | monthly platform fee |
| Graph tier | supply-chain / counterparty risk team | monthly fee + usage |
| Fleet wholesale | other cloud-itonami operators | API metering |

## Unit Economics

Track these numbers for every operator:

- source-integration hours per new jurisdiction/data source
- monthly infrastructure cost
- LLM cost per operation (upsert / relationship-draft / disclosure)
- correction/dispute handling hours per tenant
- gross margin after infrastructure and support
- churn and expansion revenue per contract tier

The business should only scale after the source catalog is genuinely
citable (never fabricated) and governor tests catch scope/source/licensing
misconfiguration before production use.

## Open Participation

Anyone may:

- fork the repository
- run the demo
- deploy a self-hosted instance
- submit issues and patches
- publish compatible source-catalog extensions (real, citable sources only)
- create a local operator business

itonami.cloud should require certification before listing an operator as a
trusted provider, routing customer leads, or allowing managed disclosure
under the platform brand.

## Operator Trust Levels

| Level | Capability |
|---|---|
| Contributor | patches, docs, issues, examples |
| Self-host operator | runs their own instance with no platform endorsement |
| Certified operator | listed on itonami.cloud after review |
| Managed operator | may receive leads and operate customer tenants |
| Core maintainer | can approve changes to governor, security and governance |

## Marketplace Metadata

Suggested itonami.cloud metadata:

```edn
{:itonami.blueprint/id "cloud-itonami-isic-8291"
 :itonami.blueprint/name "Corporate/Compliance Intelligence Actor"
 :itonami.blueprint/isic-rev4 "8291"
 :itonami.blueprint/domain :corporate-intelligence/compliance
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-8291"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger]
 :itonami.blueprint/optional-technologies [:dmn :bpmn]}
```

## Non-Negotiables

- Do not commit real company or individual data.
- Do not add a schema field for private-life data (family, health,
  political/religious opinion, sexual orientation, real-time location).
- Do not bypass the DisclosureGovernor for production writes or disclosures.
- Do not serve a disclosure to a tenant without an active, registered contract.
- Do not fabricate a source-catalog entry to expand apparent coverage.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator.
