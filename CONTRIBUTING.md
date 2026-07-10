# Contributing

`cloud-itonami-isic-8291` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or disclosure
behavior.

## Rules

- Do not commit real company records, real named individuals, credentials or
  customer contract documents.
- Keep production writes and disclosures behind DisclosureGovernor.
- Treat every new record type as high-risk: add tests for scope-gate,
  source-basis, licensed-disclosure, confidence floor and audit logging.
- Never add a schema field for private-life data (family, health, political/
  religious opinion, sexual orientation, real-time location). If a proposed
  feature needs one, it does not belong in this repository — raise it as an
  ADR instead of adding the field.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
