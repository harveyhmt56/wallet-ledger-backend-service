# Project state

Fresh note: replace facts here whenever the baseline moves. Checked 2026-09-17. Application implementation baseline `a3b7f9d` (`qa/full-analyze-tests` is tree-identical); `main` head `11ea104` adds memory documentation only. The README refresh on `coder/readme-optimize` is also documentation only. [Index](../../MEMORY.md) · [history](history.md).

## Implemented

- One Spring JDBC application, Java 21 / Boot 3.5.16. PostgreSQL owns money, claims, idempotency and outbox; Redis rate-limits; Kafka carries balance snapshots.
- Provisioning, credit/debit, balance/history, transfers, full credit/debit refunds, daily/trusted/promotion rewards, outbox relay and projection, reconciliation, bounded SQL audit.
- Migrations V1–V5. V4 (`9ef2639`): indexed predecessor/tail integrity checks, pinned search paths, populated-data preflight, separate full audit. V5 (`863d63f`): durable Kafka quarantine and replay audit.
- Transfer receipts filtered to a sender allowlist; JWT issuer/audiences required outside `local`; HTTP authorization matrix and signed-JWT tests (`760f4b0`).
- Test-quality findings F-04–F-08 closed (`e6472f7`): forced refund/provision races with exact loser codes, rejection matrices, retry rollback, real Kafka listener, adapter unit tests; PIT gate widened to `ApiProblems`, `RateLimitFilter`, `RedisRateLimiter`.
- F-01 (`5ffb0d2`, step 3 first slice): known transaction-start connection failures → structured 503 with same-key retry guidance; other transaction exceptions → sanitized 500. [Evidence](../database-outage-f01.md).
- F-02 (`863d63f`, step 4 recovery slice): invalid events quarantine immediately; other listener failures get three attempts with bounded back-off; quarantine commits before source acknowledgement; audited operator replay; alert metrics. [Evidence](../kafka-quarantine-f02.md).
- F-03 (`a3b7f9d`, step 4 validation slice): `schemaVersion`, `walletSequence` and `balanceAfter` require exact JSON integer types and representable Java longs before conversion/writes; real listener regressions verify quarantine and following valid same-ID snapshots; PIT includes `BalanceProjection`. [Evidence](../balance-projection-f03.md).
- CI (`79032c9`): GitHub Actions runs `clean verify -Pmutation` on Java 21 with Testcontainers. A hosted run has never been confirmed from this machine (`origin` answers "Repository not found" for the `gh` login).

## Latest evidence (`a3b7f9d`, QA re-audit run 2026-09-16)

| Measure | Result |
| --- | --- |
| Project gate, pom unchanged, JaCoCo attached from the CLI, GraalVM 21.0.8 | 171 unit + 277 integration cases, zero failures/errors/skips; PIT 92/94 (two uncovered messaging bean bodies); 12/12 SQL mutations; 933/958 lines (97.4%), 188/188 branches; 102 s |
| Whole-service PIT with ITs as killers (scratch measurement, not the gate) | 319/335 killed (95.2%); 8 survivors all explained (4 JWT killed by hand, 2 defence in depth, 1 equivalent, 1 benign timing); 8 no-coverage; none guards money |
| Black-box and chaos, isolated stack | 484 checks in 22 scenarios: 477 passed, 7 failed (all four open findings below), 39 observations; money correct everywhere; audit empty and reconciliation consistent after Redis freeze, Kafka stop/poison, PostgreSQL stop/freeze and `SIGKILL` with 298 requests in flight |

Source: [2026-09-16 re-audit](../qa-audit-2026-09-16.md). Earlier gates are superseded; see [history](history.md#superseded-evidence).

Documentation check, 2026-09-17: [README](../../README.md) shortened around setup/tests, design, concurrency and limits; details moved to [API](../api.md) and [development](../development.md). Commands and behavior cross-checked with source/tests and official docs; relative links/anchors, shell syntax, Compose configuration and diff checks pass. No application tests or stack run for this documentation-only change; historical application evidence above is unchanged.

## Open findings

| Id | Finding | Step |
| --- | --- | --- |
| Review F4 medium | Every `DataAccessException` maps to 503 with same-key retry guidance: reproduced with a NUL byte in `reason` (SQLSTATE 22021) and a commit-trigger refusal (23514) on a corrupted wallet. Retrying cannot succeed | 3 |
| F-12 low | `GET …/balance` → `500 INTERNAL_ERROR` while a write → 503 during the window in which Hikari still hands out connections that died with the server; `ApiProblems` inspects the `TransactionException` cause chain only on the create branch | 3 |
| F-13 medium | Readiness `200 UP` with PostgreSQL stopped (Boot readiness group excludes `db`); overall health DOWN for a paused Redis the service fails open on; no Kafka indicator | 6 |
| F-14 low | `wallet.command.rejections`, `wallet.rate_limit.degraded`, `wallet.kafka.quarantined` (and three sibling counters) absent from `/actuator/metrics` after a restart until first increment; alerts on them read no-data | 6 |
| F-09 / F-10 / F-11 low | 405/415 map to `INVALID_INPUT`; 405 loses `Allow`. No JDBC socket read timeout; a request holding a connection during a DB freeze has no deadline (money stayed safe). PIT gate deliberately bounded; two unchanged Kafka bean bodies uncovered | 3 / 6 / 6 |
| Review N1 / N3 | V4 audits at application startup and blocks writers; run it as an explicit pre-deploy step. Commit triggers do not enforce per-operation sign/account semantics; inverse refunds are audit-only | 6 / later |
| Review F5 / F6 / F7 | Promotion lock taken before duplicate/exhaustion checks; history rows lack refund origin and transfer counterparty; rejection and framework error envelopes inconsistent, generic 400 bodies name no field | 5 / 3 / 3 |

Closed findings and when they were verified: [history](history.md#closed-findings). Finding and evidence detail: [2026-09-16 re-audit](../qa-audit-2026-09-16.md#findings-report-id--project-id).

## Pending remediation

Steps 3–6 of the [ordered plan](../review-remediation-plan.md#ordered-implementation-plan); steps 1–2 are done and verified.

3. **Errors and audit fields (F-01 slice done).** SQLSTATE-based classification in `ApiProblems` (08/57P0x → 503 retry; 22 → 400 naming the field; 23 → 409 for known constraints, else 500) applied to both `DataAccessException` and every `TransactionException` — closes Review F4 and F-12 together; field-level 400 bodies; keep `Allow`; `originalTransactionId` and transfer counterparty in history; real rate-limit retry guidance.
4. **Messaging (F-02 and F-03 done).** Wire `ledger.outbox.topic`; two-relay lease test; production replication and minimum ISR; deploy the documented quarantine alerts/operator privileges.
5. **Promotion contention.** Unlocked early rejection for already-claimed or exhausted campaigns; keep the locked authoritative check for winners.
6. **Production evidence.** Readiness group includes `db` (F-13); eager counter registration (F-14); controlled V4 migration step; JDBC socket timeout; enforce Java 21 in the build (unit tests pass silently on JDK 24); container patch review (PostgreSQL 17.6 is behind 17.11); JaCoCo threshold in the pom; backup/restore and recovery drills; confirmed hosted CI. README refresh completed 2026-09-17.

Verdict: every mandatory and supporting feature implemented and verified; production readiness is **not** established. This queue does not authorize implementation; the user's current request does.

## Next action

README refresh is complete; no application change is in progress and application code remains at `a3b7f9d`. The re-audit's ordered test additions are in [QA worth adding](../qa-audit-2026-09-16.md#qa-worth-adding-in-order); the first item (SQLSTATE classification with its `ApiProblemsTest` matrix, NUL-byte and read-during-outage cases) closes two findings at once. This note does not authorize that slice.
