# Wallet ledger project memory

Application implementation baseline: `a3b7f9d` (F-03 committed); `main` head `11ea104` adds memory documentation only. Checked 2026-09-17; the README refresh on `coder/readme-optimize` is also documentation only. This is a retrieval index and source-backed snapshot, not a backlog to execute.

## Project goal and must-fulfil requirements

Goal: a production-grade wallet ledger for one whole-unit in-game currency where **the balance is always right, no matter how many requests arrive, in what order, or what breaks along the way**.

Must fulfil (graded on money-moving correctness, service design, concurrency and edge cases, test quality, documentation):
- Credit, debit, reject an insufficient-balance debit, current balance, paginated history; every balance change leaves a permanent record of what and why.
- Idempotent requests; no incorrect state under concurrent requests; no partial updates on failure; clear handling of invalid input (negative amounts, missing player).
- Tests that protect money paths under pressure: concurrent requests and repeated submissions.
- README with run/setup/database/tests, design decisions and ledger approach, concurrency & idempotency, testing approach, assumptions & limitations.
- Supporting scope, all implemented: daily login streak, player-to-player transfer, full refund/reversal, server-decided reward claim, first-N promotion, domain events on balance change.

Required stack: Java 21, Spring Boot 3.5.16, PostgreSQL, Redis, Kafka, Flyway, Docker Compose. Full list, confirmed decisions and invariants: [requirements](docs/memory/requirements.md).

## Current state (2026-09-17, `a3b7f9d`)

- Every mandatory and supporting feature is implemented, committed and verified. Closed: quadratic commit check and TEMP-shadow bypass (V4, `9ef2639`), recipient-balance disclosure and HTTP authorization matrix (`760f4b0`), test-quality findings F-04–F-08 (`e6472f7`), F-01 stopped-database error contract (`5ffb0d2`), F-02 durable Kafka quarantine (`863d63f`), F-03 exact event integers (`a3b7f9d`).
- Second full QA audit at `a3b7f9d` (2026-09-16): 171 unit + 277 IT green, 97.4% lines / 100% branches, whole-service PIT 95.2%, 484 black-box checks with money correct everywhere and the ledger reconciled after every chaos step. Nothing new broke. [Summary](docs/qa-audit-2026-09-16.md).
- Open, all non-money: Review F4 blanket `DataAccessException` → 503 (NUL byte gets retry guidance), F-12 balance read → 500 in the dead-connection window of a DB outage, F-13 readiness UP with PostgreSQL stopped, F-14 counters absent until first use; plus F-09/F-10/F-11 and review items N1/N3/F5–F7 (low). Remediation steps 3–6 remain partly pending. Production readiness is **not** established. Details and next action: [state](docs/memory/state.md).

## Sub-memories: read only what the task needs; do not preload notes

| Need | Note |
| --- | --- |
| Goals, must-fulfil requirements, grading, confirmed decisions, invariants | [requirements](docs/memory/requirements.md) |
| Current baseline, fresh evidence, open findings, pending steps, next action | [state](docs/memory/state.md) |
| Where behavior lives: posting, locks, idempotency, schema, API, security, messaging | [implementation](docs/memory/implementation.md) |
| Commands, test navigation, evidence rules and limits | [verification](docs/memory/verification.md) |
| Compact dated timeline of past reviews, fixes and superseded evidence | [history](docs/memory/history.md) |
| Quickstart and design / credentials and endpoints / host setup and configuration | [README](README.md) / [API](docs/api.md) / [development](docs/development.md) |

Canonical detail documents: [remediation plan](docs/review-remediation-plan.md) (findings, steps 1–6, acceptance criteria), [V4 evidence](docs/ledger-integrity-v4.md), [step 2 evidence](docs/api-security-step2.md), [QA audit 2026-09-14](docs/qa-audit-2026-09-14.md), [F-01](docs/database-outage-f01.md) / [F-02](docs/kafka-quarantine-f02.md) / [F-03](docs/balance-projection-f03.md) evidence, [QA re-audit 2026-09-16](docs/qa-audit-2026-09-16.md).

## Retrieval and maintenance

1. Check `git status --short` and `git log -5 --oneline` first; if the baseline moved, inspect the committed diff before trusting a note.
2. Read this index, then one note and the linked source or tests. Use `rg` for symbols instead of loading every document.
3. The user's current instruction sets scope. Code establishes behavior; tests and reports establish only what they exercise; memory and reviews are summaries, not proof or authorization.
4. After a meaningful change, update `state.md` (baseline, evidence, open items) and the affected note, then add one dated row to `history.md`. An audit that publishes only an external report gets a short in-tree summary under `docs/` so the row has something to link. Separate **decided**, **implemented**, **verified**, **historically reported** and **pending**. Never mark a fix verified without fresh evidence.
5. Keep this index under about 50 lines and each note under about 80. Move superseded facts to `history.md`, never into active notes. The goal and must-fulfil block above must survive every rewrite.
6. Before a context reset, record only objective, completed changes, evidence, blocker and next action with file and symbol in `state.md`. No transcripts, tool output or secrets.

[AGENTS.md](AGENTS.md) routes agents here; `MEMORY.md` is requested explicitly by that file, not a built-in instruction filename.
