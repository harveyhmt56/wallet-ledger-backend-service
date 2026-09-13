# Project state

Fresh note: replace facts here whenever the baseline moves. Checked 2026-09-14. Audit baseline `f2a7e07`; working branch `codex/QA-full-project-verify` adds four integration cases and QA evidence. Application code and migrations are unchanged since `760f4b0`. [Index](../../MEMORY.md) · [history](history.md).

## Implemented

- One Spring JDBC application, Java 21 / Boot 3.5.16. PostgreSQL owns money, claims, idempotency and outbox; Redis rate-limits; Kafka carries balance snapshots.
- Provisioning, credit/debit, balance/history, transfers, full credit/debit refunds, daily/trusted/promotion rewards, outbox relay and projection, reconciliation, bounded SQL audit.
- Migrations V1–V4. V4 (`9ef2639`, step 1): indexed predecessor/tail integrity checks, pinned search paths, populated-data preflight, separate full audit. Closed the quadratic commit cost (24.9 s → ~1.7 ms per credit at 20k entries) and the TEMP-table shadow bypass.
- Step 2 (`760f4b0`): transfer receipts filtered to a sender allowlist on fresh and stored replay; JWT issuer/audiences required outside `local`; 81 controller authorization/input cases; 12 signed-JWT HTTP cases.
- Test-gap closure (`e6472f7`, tests and `pom.xml` only): forced refund/provision races with exact loser codes; missing/suspended-player and rejection matrices for wallet and rewards; retry rollback, three-attempt limit and JSON canonicalization; first-delivery/zero-balance projection; real Kafka listener with committed offsets; unit tests for advice, rate-limit filter, relay, projection parser and reward controller. The normal PIT gate now also targets `ApiProblems`, `RateLimitFilter`, `RedisRateLimiter`.
- CI (`79032c9`): GitHub Actions runs `clean verify -Pmutation` on Java 21 with Testcontainers. A hosted run has never been confirmed from this machine.

## Latest evidence (fresh, 2026-09-14 at `f2a7e07` plus audit tests)

| Measure | Result |
| --- | --- |
| Gate with combined JaCoCo | 97 unit + 268 integration cases, zero failures/errors/skips; PIT 52/52; 12 SQL mutations detected; 78 s |
| Unit-only JaCoCo | Not remeasured in this audit; historical 2026-09-13: 270/900 lines, 66/164 branches |
| Combined JaCoCo (command-line attachment; not in POM) | 875/900 lines (97.2%), 164/164 branches (100%); application unchanged |
| Broader PIT (clean baseline scratch, all classes, signed-JWT IT excluded) | 290/307 killed (94.5%), 9 survived, 8 uncovered, 0 timeouts/errors |
| Fresh manual mutations | 4 new money-path mutations and 5 adapter mutations detected; matching restored controls pass. Null security-chain mutation also causes 8 test-body errors, separately disclosed |

Sources: [2026-09-14 audit](../qa-audit-2026-09-14.md), [final counts](../evidence/qa-2026-09-14/final-gate-summary.json). New tests: two-instance HTTP debit/duplicate/mixed races with complete durable history/event assertions, and forced debit-after-commit rejection. Fresh probes reproduce F-01/F-02/F-03; outage recovery preserves money. One initial container-restart harness error was corrected; final selected cases pass. All audit containers removed; existing Compose untouched. Historical evidence: [2026-09-13 tests](../test-gap-evidence-2026-09-13.md), [2026-09-11 audit](../qa-audit-2026-09-11.md).

## Open findings

| Id | Finding | Step |
| --- | --- | --- |
| F-01 medium (was high) | Fresh outage → generic HTTP 500 after 5.824 s; same-key recovery is safe. `CannotCreateTransactionException` is a `TransactionException`, so `ApiProblems` never sees it | 3 |
| F-02 high | One invalid Kafka event can stall the partitions handled by its consumer: single listener thread, unlimited retry, no durable quarantine. Fresh probe: poison at p0/o1 stalls later records at p0/o2 and p1/o1 during a 3-second window; assignments and offsets retained | 4 |
| F-03 medium | Fresh oversized sequence/balance wrap to `Long.MAX_VALUE`/0 and block later valid snapshots; schema-version coercion also confirmed. Authoritative wallet stays correct | 4 |
| F-09 / F-10 low | 405/415 map to `INVALID_INPUT`; 405 loses `Allow`. No JDBC socket read timeout; historical frozen-database probe exceeded 12 s (money stayed safe); not rerun on 2026-09-14 | 3 / 6 |
| F-11 low | The configured PIT gate is deliberately bounded (domain classes plus five adapters); the 94.5% broader score is measured, not gated | 6 |
| Review N1 | V4 applies at application startup and blocks writers while it audits; run it as an explicit pre-deploy migration step | 6 |
| Review N3 | Commit triggers do not enforce per-operation sign/account semantics; inverse refunds are audit-only | later |
| Review F4 / F5 / F6 / F7 | Every `DataAccessException` maps to 503; promotion lock taken before duplicate/exhaustion checks; history rows lack refund origin and transfer counterparty; stored rejections and framework errors use inconsistent problem+json envelopes and generic 400 bodies name no field | 3 / 5 / 3 / 3 |

Closed by `e6472f7` (2026-09-13): F-04 refund-lock blind spot, F-05 debit reversal never executed, F-06 missing player untested, F-07 status-only race assertions, F-08 production paths never run under `verify`. Broader-PIT survivors (refund `>` vs `>=`, early prechecks, retry back-off arithmetic, bean null-returns, eight uncovered gauge/bootstrap mutants) are documented limits, not defects.

## Pending remediation

Steps 3–6 of the [ordered plan](../review-remediation-plan.md#ordered-implementation-plan); steps 1–2 are done and verified.

3. **Errors and audit fields.** 409 for known conflicts, 503 for transient failures including `CannotCreateTransactionException`, 500 for unexpected ones; field-level 400 bodies; keep `Allow`; `originalTransactionId` and transfer counterparty in history; real rate-limit retry guidance.
4. **Messaging.** Wire `ledger.outbox.topic`; strict event DTO with long-range checks; `IllegalArgumentException` not retryable, bounded back-off, durable quarantine with replay; two-relay lease test; production replication and minimum ISR.
5. **Promotion contention.** Unlocked early rejection for already-claimed or exhausted campaigns; keep the locked authoritative check for winners.
6. **Production evidence.** Controlled V4 migration step; readiness semantics; container patch review (PostgreSQL 17.6 is behind 17.11); JDBC socket timeout; JaCoCo threshold in the pom; backup/restore and recovery drills; confirmed hosted CI; README refresh.

Verdict: core features implemented and verified; production readiness is **not** established. This queue does not authorize implementation; the user's current request does.

## Next action

Current request authorized QA and automated tests; they are complete. No production fixes were made. For a future remediation slice, start with step 3, F-01: classify transaction-start dependency failures and unexpected transaction failures in [ApiProblems](../../src/main/java/com/example/walletledger/configuration/ApiProblems.java), verified by stopped-database HTTP/recovery tests. A frozen existing connection (F-10) needs a separate timeout/recovery test.
