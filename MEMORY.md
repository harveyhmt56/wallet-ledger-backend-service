# Wallet ledger project memory

Checked 2026-09-17 at `6753811`. The money implementation baseline is `a3b7f9d`; later commits reorganized documentation and restricted Actuator health details to administrators. This index is a source-backed snapshot, not permission to implement pending work.

## Goal and requirements

Build a production-grade wallet ledger for one whole-unit in-game currency where the balance remains correct under concurrency, retries, reordering, and failures.

The service must provide credit, debit with insufficient-funds rejection, current balance, paginated history, permanent explanatory entries, idempotency, atomic failure behavior, and pressure tests for concurrent and repeated requests. Supporting scope includes daily login streaks, transfers, full reversals, server-decided rewards, first-N promotions, and balance-change events.

Required stack: Java 21, Spring Boot 3.5.16, PostgreSQL, Redis, Kafka, Flyway, and Docker Compose. Confirmed product decisions and invariants: [requirements](docs/memory/requirements.md).

## Current state

- Mandatory and supporting features are implemented. Closed issues are summarized in [history](docs/memory/history.md).
- The last full application gate and black-box/chaos audit were completed at `a3b7f9d` on 2026-09-16; money remained correct and the ledger reconciled. Later changes have not received another full application gate in the retained evidence.
- Open findings concern error classification, outage/readiness behavior, lazy metrics, deployment durability, history clarity, and operational hardening. Production readiness is **not** established. See [state](docs/memory/state.md).

## Read by need

| Need | Document |
| --- | --- |
| Requirements, decisions, invariants | [requirements](docs/memory/requirements.md) |
| Current baseline, open issues, next action | [state](docs/memory/state.md) |
| Source map and implementation facts | [implementation](docs/memory/implementation.md) |
| Commands, test map, evidence rules | [verification](docs/memory/verification.md) |
| Closed-issue summaries | [history](docs/memory/history.md) |
| API / development / production procedures | [API](docs/api.md) · [development](docs/development.md) · [operations](docs/operations.md) |

## Maintenance

1. Check `git status --short` and recent commits before trusting a snapshot.
2. Read this index, one relevant note, and the linked source/tests; use `rg` rather than loading every document.
3. Code establishes behavior. Tests establish only what they execute. Never call a change verified without a fresh recorded run.
4. When behavior or evidence changes, update `state.md`, the affected note, and one short row in `history.md`. Keep superseded measurements out of active documentation.

[AGENTS.md](AGENTS.md) routes project work here.
