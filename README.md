# cloud-itonami-isic-8291

Open Business Blueprint for **ISIC Rev.4 8291**: activities of collection
agencies and credit bureaus. This repository publishes a corporate/
compliance intelligence SaaS — the D&B / Moody's Orbis(BvD) / Refinitiv
World-Check class of business — as an OSS business that any qualified
operator can fork, deploy, run, improve and sell.

A **Palantir-style** actor design: hold curated corporate/compliance data
internally, disclose it only to contracted, licensed users. Built on this
workspace's [`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints) — the same actor pattern as
[`cloud-itonami-6310`](https://github.com/gftdcojp/cloud-itonami-6310) and
[`cloud-itonami-M6910`](https://github.com/cloud-itonami/cloud-itonami-M6910).

> **Why an actor layer at all?** A Dossier-LLM is great at normalizing
> registry filings, drafting relationship edges between entities, and
> proposing disclosure column sets — but it has **no notion of scope
> boundaries, source provenance, licensing entitlement, or a data subject's
> right to dispute a record**. Letting it write or disclose facts directly
> invites unsourced (defamatory) relationship claims, over-disclosure beyond
> a contract's tier, and scope creep into private-life profiling. This
> project seals the Dossier-LLM into a single node and wraps it with an
> independent **DisclosureGovernor**, a human **review workflow**, and an
> immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor holds and discloses **professional/official-capacity public-record
facts only**: company registry data, officers/directors/UBO where legally
disclosed, government agencies and officials **in their official role**,
publicly-disclosed ownership/directorship/JV relationships, and business
contact information. Every fact must cite a real, verifiable source
(`src/dossier/facts.cljc`).

**There is no field anywhere in this schema for private-life data** — home
address, family, health, political/religious opinion, sexual orientation, or
real-time location. This is not a runtime filter someone could forget to
call; the schema simply has no such field, and a second runtime check
(`DisclosureGovernor`'s scope-gate) rejects any proposal that tries to smuggle
one in. See `90-docs/adr/2607110400-*` in the superproject and
`docs/adr/0001-architecture.md` here for the full reasoning.

## Consuming this actor from another blueprint

Four governed read ops are the actual product surface, all gated by the
DisclosureGovernor's licensed-disclosure check (no bypass):

| op | answers | min tier |
|---|---|---|
| `:disclosure/query` | a company profile (columns limited to your tier) | `:tier/basic` |
| `:disclosure/screen-name` | is this NAMED person PEP/sanctions-flagged? (exact match only in R0) | `:tier/compliance` |
| `:disclosure/ownership-chain` | who owns this company, per our sourced relationship data (one hop) | `:tier/graph` |
| `:disclosure/relationship-check` | does this named person have a professional-capacity relationship with this company (org membership or a direct edge, one hop) | `:tier/graph` |

Real consumers so far: [`cloud-itonami-isic-6910`](https://github.com/cloud-itonami/cloud-itonami-isic-6910)
(company formation), [`-6810`](https://github.com/cloud-itonami/cloud-itonami-isic-6810)
(real estate), [`-6499`](https://github.com/cloud-itonami/cloud-itonami-isic-6499)
(VC fund), [`-6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512)
(non-life insurance), and [`-6419`](https://github.com/cloud-itonami/cloud-itonami-isic-6419)
(banking) all call `:disclosure/screen-name` from their own KYC/sanctions
advisor. `:disclosure/ownership-chain` and `:disclosure/relationship-check`
were added for [`-6420`](https://github.com/cloud-itonami/cloud-itonami-isic-6420)
(holdco beneficial-ownership verification) and
[`-6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621)/[`-6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622)
(adjuster/broker conflict-of-interest) — wiring those three is a follow-up,
not done yet. Five other pilot blueprints declare `:corporate-intelligence`
as an optional technology without calling it (ADR-2607110400 addenda).

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
decision record. See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud.

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, DisclosureGovernor, governed disclosure, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, deploy, support and sell the service |
| Trust controls | Governance, security reporting, policy tests, audit requirements |

The primary industry classification is **ISIC Rev.4 8291** because the
commercial activity is collecting and disseminating standing/relationship
information about enterprises and their professional-capacity officials.

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌────────────┐     proposal      ┌────────────────────┐
   │ Dossier-LLM │ ────────────────▶ │ DisclosureGovernor │  (independent system)
   │ (sealed)    │  draft + source   │  scope · source ·  │
   └────────────┘   citation         │  license · human   │
                                     └────────────────────┘
                                              │
                                   commit / disclose only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: Dossier-LLM never writes, discloses, or resolves a
correction the DisclosureGovernor would reject.

## Run

```bash
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 5-operation demo through one OperationActor
clojure -M:lint
```

## Live data (UK Companies House)

R0 ships one real live-data seam, not just demo fixtures: `dossier.live-
store/live-store` decorates a `MemStore`/`DatomicStore` with a fallback to
the real [Companies House public data API](https://developer.company-information.service.gov.uk/)
for `company-by-name` and `officials-of` a known GBR company id — local/
seeded data always wins when present, so this can only ADD coverage, never
change an existing answer.

```bash
export COMPANIES_HOUSE_API_KEY=...   # get one free at the URL above
clojure -M:dev -e "(require '[dossier.live-store :as live] '[dossier.operation :as op]) (op/build (live/live-store))"
```

Without the env var, `live-store` behaves exactly like the undecorated
local store — no crash, no partial data, `dossier.companies-house/
configured?` reports `false`. See `dossier.facts/coverage`'s `:live-
capable-jurisdictions` (currently `#{:gbr}` only) for the honest, static
scope — `:disclosure/screen-name` does NOT yet benefit from live data
(officer-by-name search needs a second live API hop not yet built, see
`dossier.companies-house`'s docstring); only company lookups and a known
company's officer list do.

## Non-Negotiables

- Do not commit real company or individual data.
- Do not add a schema field for private-life data.
- Do not bypass the DisclosureGovernor for production writes or disclosures.
- Do not serve a disclosure without an active, registered contract.
- Do not fabricate a source-catalog entry.

License: AGPL-3.0-or-later.
