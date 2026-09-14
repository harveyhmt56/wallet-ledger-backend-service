# Wallet ledger project memory

Baseline: `6ba729d` plus verified F-01 working-tree changes on `coder/recovery-db-outage`. Last checked 2026-09-14. This is a retrieval index and source-backed snapshot, not a backlog to execute.

## Project goal and must-fulfil requirements

Goal: a production-grade wallet ledger for one whole-unit in-game currency where **the balance is always right, no matter how many requests arrive, in what order, or what breaks along the way**.

Must fulfil (graded on money-moving correctness, service design, concurrency and edge cases, test quality, documentation):
- Credit, debit, reject an insufficient-balance debit, current balance, paginated history; every balance change leaves a permanent record of what and why.
- Idempotent requests; no incorrect state under concurrent requests; no partial updates on failure; clear handling of invalid input (negative amounts, missing player).
- Tests that protect money paths under pressure: concurrent requests and repeated submissions.
- README with run/setup/database/tests, design decisions and ledger approach, concurrency & idempotency, testing approach, assumptions & limitations.
- Supporting scope, all implemented: daily login streak, player-to-player transfer, full refund/reversal, server-decided reward claim, first-N promotion, domain events on balance change.

Required stack: Java 21, Spring Boot 3.5.16, PostgreSQL, Redis, Kafka, Flyway, Docker Compose. Full list, confirmed decisions and invariants: [requirements](docs/memory/requirements.md).

## Current state (2026-09-14)

- Implemented and verified locally: every mandatory and supporting feature. V4 (`9ef2639`) closed the quadratic commit check and TEMP-shadow bypass; step 2 (`760f4b0`) closed the recipient-balance disclosure and added the HTTP authorization matrix and signed-JWT tests; `e6472f7` closed test-quality findings F-04–F-08 (tests and `pom.xml` only).
- F-01 fixed and verified in the working tree: transaction-start connection failures → structured 503; unexpected transaction failures → safe 500. Full gate: 117 unit + 269 integration cases, no failures/errors/skips; PIT 68/68; SQL mutations 12/12. [Evidence](docs/database-outage-f01.md). Prior audit JaCoCo 97.2%/100% and broader PIT 290/307 are historical, not remeasured for this fix.
- Open: F-02 poison Kafka record stalls later records; F-03 oversized event integers corrupt the projection; F-09/F-10/F-11 and review items N1/N3/F4–F7 low. Remaining steps 3–6 pending. Production readiness is **not** established. Details and next action: [state](docs/memory/state.md).

## Sub-memories: read only what the task needs; do not preload notes

| Need | Note |
| --- | --- |
| Goals, must-fulfil requirements, grading, confirmed decisions, invariants | [requirements](docs/memory/requirements.md) |
| Current baseline, fresh evidence, open findings, pending steps, next action | [state](docs/memory/state.md) |
| Where behavior lives: posting, locks, idempotency, schema, API, security, messaging | [implementation](docs/memory/implementation.md) |
| Commands, test navigation, evidence rules and limits | [verification](docs/memory/verification.md) |
| Compact dated timeline of past reviews, fixes, closed findings and superseded evidence | [history](docs/memory/history.md) |
| Setup, credentials, endpoints, demo, environment variables | [README](README.md) |
| Original 2026-09-07 plan; historical, load only when needed | [archive](docs/memory/archive/2026-09-07-plan.md) |

Canonical detail documents: [remediation plan](docs/review-remediation-plan.md) (findings, steps 1–6, acceptance criteria), [V4 evidence](docs/ledger-integrity-v4.md), [step 2 evidence](docs/api-security-step2.md), [QA audit](docs/qa-audit-2026-09-11.md), [test-gap evidence](docs/test-gap-evidence-2026-09-13.md), [independent review](docs/review-by-harvey-with-claude.md), [build evidence](docs/build-evidence.md).

## Retrieval and maintenance

1. Check `git status --short` and `git log -5 --oneline` first; if the baseline moved, inspect the committed diff before trusting a note.
2. Read this index, then one note and the linked source or tests. Use `rg` for symbols instead of loading every document or the archive.
3. The user's current instruction sets scope. Code establishes behavior; tests and reports establish only what they exercise; memory and reviews are summaries, not proof or authorization.
4. After a meaningful change, update `state.md` (baseline, evidence, open items) and the affected note, then add one dated row to `history.md`. Separate **decided**, **implemented**, **verified**, **historically reported** and **pending**. Never mark a fix verified without fresh evidence.
5. Keep this index under about 50 lines and each note under about 80. Move superseded facts to `history.md`, never into active notes. The goal and must-fulfil block above must survive every rewrite.
6. Before a context reset, record only objective, completed changes, evidence, blocker and next action with file and symbol in `state.md`. No transcripts, tool output or secrets.

[AGENTS.md](AGENTS.md) routes agents here; `MEMORY.md` is requested explicitly by that file, not a built-in instruction filename.
