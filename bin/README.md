# LEI Discovery Automation (`bin/collect.cljs`)

## Purpose

Automate the scaffold and registration of `cloud-itonami-lei-<LEI>` repositories based on discovered company legal entities and their Terms of Service.

## Input

- `90-docs/adr/2607110300-cloud-itonami-lei-corporate-tos-catalog.progress.edn` (ISIC axis)
- `90-docs/adr/2607110300-cloud-itonami-lei-corporate-tos-catalog.isco-progress.edn` (ISCO axis)

Each file lists verticals/occupations with their pending company candidates (`:industry/status :pending` or `:occupation/status :pending`).

## Output

For each pending vertical:

1. **Call isic-8291**: `(dossier.operation/build {:op :disclosure/discover-candidates :vertical {...} :count 3})`
   - Returns: `{:summary :rationale :cites :source :effect :value :confidence}`
   - Governor approves via standard 6-stage pipeline (source-basis HARD gate)

2. **GLEIF lookup**: `GET https://leidata.gleif.org/api/v1/lei-records?filter[entity.legalName]=<name>`
   - Extracts: LEI, legal name, country

3. **ToS HTTP fetch**: `curl -s <tos-url>`
   - Captures plaintext Terms of Service document
   - On 403/404 or JS-rendered (empty body): logs to `retry-js-rendered.edn` for manual chrome-automation

4. **Repo scaffold**: Create `orgs/cloud-itonami/cloud-itonami-lei-<LEI>/`
   - `README.md` — company metadata
   - `blueprint.edn` — actor manifest
   - `tos.journal.edn` — append-only ToS document ledger
   - `LICENSE`, `NOTICE` — standard boilerplate

5. **Progress ledger**: Update `progress.edn`
   - Set `:industry/status :done` or `:failed`
   - Record each company's `:company/status` and `:company/tos-doc-count`
   - Log `:company/failure-reason` honestly (no fabrication)

## Limitations (Honest)

- **HTTP**: Plain-text curl only. JS-rendered SPAs fail with no workaround in-script.
- **Git push**: Manual `git push` required per repo (not automated in this pass).
- **Async**: nbb `slurp` unavailable; recommend `bb` (babashka) for JVM file I/O, or shell wrapper.

## Skeleton Status

Current `collect.cljs` is a design skeleton: it structures the flow, lists pending rows, stubs out the core steps.

**Next implementation steps**:
1. Migrate to `bb` (babashka) for full HTTP + file I/O
2. Integrate actual GLEIF API call (v1 JSON response parsing)
3. Implement ToS fetch + failure classification
4. Repo scaffold + git init + commit logic
5. Progress.edn atomic update (append-only ledger)
6. E2E test on 1–2 pending companies (dry-run, then live)

## Related

- **ADR-2607182300** — Part C full design (this is skeleton)
- **ADR-2607110300** — Progress tracker + methodology
- **ADR-2607110400** — Advisor⊣Governor pattern (6-stage Governor reused)
- **ADR-2607150100** — GLEIF lookup is NOT governor-gated (deterministic API)

## Testing

**Dry-run** (no scaffolding, no push):
```bash
nbb bin/collect.cljs --dry-run --scope=isic
```

**Live** (requires implementation completion):
```bash
nbb bin/collect.cljs --scope=all
```

## Notes

As of 2026-07-18:
- **ISIC axis**: ROLLOUT COMPLETE (all 144 rows processed, 100 repos created)
- **ISCO axis**: ROLLOUT COMPLETE (all 90 occupations classified, 9 new repos created, no further pending rows)

A future tick would require either:
1. JS-capable fetch tool (chrome-automation) for retry-queued pages
2. Owner direction for new scope (e.g., COFOG / UNSPSC verticals)
