# Wallet ledger project memory

Last checked: 2026-09-09 against baseline `79032c9` plus the V4 remediation change; see [fresh evidence](docs/memory/verification.md).
This is a retrieval index and source-backed snapshot, not an instruction to execute a backlog.

## Requirement:
- Wallet-ledger-backend-service for Production grade.
- Required wallet functions: credit/debit, insufficient-funds rejection, current balance, paginated history, permanent reason/reference records, idempotency, concurrency safety, atomic failures and clear input errors. Supporting scope includes the implemented rewards, transfers, refunds and events.
- Safety & Correctness: request idempotent, prevent concurrent problem, transaction atomic and input validation.
- In one line: the wallet balance must always be right, no matter how many requests come in, in what order, or what breaks along the way.
- Good Documentation: README with clear instruction for quick start and test this project, and design reasoning.

## Current state

- Base implementation is `f4e6b1a`; reviews followed, and `79032c9` added CI. Remediation step 1 now adds V4 while preserving V1–V3.
- Java 21 / Spring Boot 3.5.16; one Spring JDBC application. PostgreSQL owns money, claims, idempotency and outbox; Redis rate limits; Kafka carries balance snapshots.
- Provisioning, credit/debit, balance/history, transfers, full credit/debit refunds, daily/trusted/promotion rewards, outbox/projection and reconciliation are implemented.
- V4 implements indexed predecessor/final-tail checks, trusted integrity-function name resolution, populated-data preflight and a separate bounded operational audit. Fresh real-PostgreSQL regression, upgrade, deadline and long-history evidence is recorded.
- Remaining production work includes transfer receipt privacy, authorization/error coverage, messaging durability/recovery and operational deployment. See the evidence note before claiming readiness.

## Read only what the task needs

| Need / search terms | Open |
| --- | --- |
| Currency, refund scope, trusted evidence, invariants, assignment expectations | [Decisions](docs/memory/decisions.md) |
| Posting, savepoints, locks, idempotency, rewards, API, security, SQL, Kafka, Redis | [Implementation map](docs/memory/implementation.md) |
| Test commands, evidence limits, production gaps, next remediation step | [Verification and pending work](docs/memory/verification.md) |
| Setup, credentials, endpoint bodies, demo, environment variables | [README](README.md) |
| Detailed findings and ordered acceptance criteria | [Fact-checked remediation plan](docs/review-remediation-plan.md) |
| V4 upgrade, audit invocation/alerts, regression and long-history evidence | [Ledger integrity V4](docs/ledger-integrity-v4.md) |
| Original planning rationale, alternatives and reference list | [Archived plan](docs/memory/archive/2026-09-07-plan.md) — historical; load only when needed |

## Retrieval and maintenance

1. Check `git status --short` and `git log -5 --oneline`. If the baseline changed, inspect the relevant committed diff before trusting a note; distinguish uncommitted work.
2. Read this index, then one relevant note and the linked source/tests. Use `rg` for symbols or headings instead of loading all docs, reports or the archive.
3. Current user instructions govern task scope. Code/configuration establish implemented behavior; tests/reports establish only what they exercise. Memory and reviews are summaries, not proof or new authorization. Resolve contradictions against the relevant source and record uncertainty.
4. After a meaningful change, update the affected note's fact, source and checked date/commit. Separate **confirmed decision**, **implemented**, **historically reported**, and **pending**. Do not mark a fix or test verified without evidence.
5. Keep this index under about 60 lines and each active note under about 120 lines. Link to canonical docs for detail; move superseded history to the archive. These are project conventions, not Codex limits.
6. Before a context reset, record only durable decisions and unfinished work: objective/scope, completed changes, evidence, blocker (if any), and next action with file/symbol. Replace a stale handoff when completed; avoid accumulating session transcripts, tool output, secrets or duplicate checklists.

The small root [AGENTS.md](AGENTS.md) routes Codex to this index. `MEMORY.md` is explicitly requested by that file; it is not assumed to be a built-in instruction filename. See [official instruction discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md).
