# cloud-itonami-isic-8291

Open Business Blueprint for **ISIC Rev.4 8291**: activities of collection
agencies and credit bureaus. This repository publishes a corporate/
compliance intelligence SaaS — the D&B / Moody's Orbis(BvD) / Refinitiv
World-Check class of business — as an OSS business that any qualified
operator can fork, deploy, run, improve and sell.

A **Palantir-style** actor design: hold curated corporate/compliance data
internally, disclose it only to contracted, licensed users. Built on this
workspace's [`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
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

## Non-Negotiables

- Do not commit real company or individual data.
- Do not add a schema field for private-life data.
- Do not bypass the DisclosureGovernor for production writes or disclosures.
- Do not serve a disclosure without an active, registered contract.
- Do not fabricate a source-catalog entry.

License: AGPL-3.0-or-later.
