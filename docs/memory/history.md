# Closed issues and compact history

Current facts and open work live in [state](state.md). This file keeps only short closure summaries; detailed historical reports and raw metrics were removed because Git history already preserves them.

## Closed findings

- `9ef2639` (2026-09-09): replaced quadratic full-history commit validation with indexed predecessor/tail checks, added populated-data preflight and the bounded full audit, and closed the TEMP-shadow bypass by qualifying persistent names and pinning trusted search paths.
- `760f4b0` (2026-09-10): removed recipient balances from new and replayed transfer responses; added the HTTP role/owner matrix and signed-JWT coverage.
- `e6472f7` (2026-09-13): closed test gaps F-04–F-08 with forced refund/provision races, debit-reversal and missing-player coverage, stronger rejection assertions, real listener coverage, and a wider PIT gate.
- `5ffb0d2` (2026-09-14): closed F-01 for transaction-start outages with structured 503 same-key retry guidance and safe 500 handling for unexpected transaction failures. The broader classifier and read-path gap remain open as Review F4/F-12.
- `863d63f` (2026-09-15): closed F-02 by adding V5 durable Kafka quarantine, bounded retry, recovery-failure offset retention, deduplication after restart, and audited authoritative replay.
- `a3b7f9d` (2026-09-16): closed F-03 by requiring exact JSON integer types and Java-long ranges before projection writes; invalid events quarantine without advancing projection state.
- `6753811` (2026-09-17): restricted Actuator health component details to `ADMIN`; public callers still receive overall health status.

## Evidence milestones

- 2026-09-16 at `a3b7f9d`: the last retained full unit/integration/mutation/coverage gate passed. Isolated black-box and chaos checks found no money or reconciliation failure; the unresolved non-money findings are preserved in [state](state.md#open-findings).
- 2026-09-17: README/API/development documentation was checked against source and primary documentation. No application test was inferred from documentation-only work.
- 2026-09-17 working tree: superseded reviews, dated QA reports, raw evidence, and benchmark artifacts were removed; durable facts and open issues were consolidated into the memory notes, and operational procedures into [operations](../operations.md).

Older review prose, raw JSON summaries, reproducers, benchmark samples, and dated QA reports remain recoverable from Git history when forensic detail is needed.
