# Governance

`cloud-itonami-isic-8291` is an OSS open-business blueprint. Governance covers
both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- Dossier-LLM cannot directly commit, disclose or resolve a correction request.
- DisclosureGovernor remains independent of the advisor.
- hard governor violations (scope-gate, source-basis, licensed-disclosure)
  cannot be overridden by human approval.
- a correction/dispute request never auto-resolves, at any rollout phase.
- every commit, hold and disclosure event is auditable.
- no schema field exists for private-life data — scope is structural, not a
  runtime filter someone could forget to call.
- real company/individual data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, disclosure scope, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and data-flow
review.

Certified operators can lose certification for:

- bypassing governor checks
- disclosing data to an uncontracted party
- ingesting or serving private-life data outside the documented scope
- misrepresenting certification status
- failing to respond to security incidents or subject correction requests
- hiding material changes to customer-facing operation
