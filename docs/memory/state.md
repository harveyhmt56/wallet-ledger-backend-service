# Project state

Fresh note: replace facts here whenever the baseline moves. Checked 2026-09-15 at `main` = `5ffb0d2`, working tree clean. Migrations unchanged since `760f4b0`. [Index](../../MEMORY.md) · [history](history.md).

## Implemented

- One Spring JDBC application, Java 21 / Boot 3.5.16. PostgreSQL owns money, claims, idempotency and outbox; Redis rate-limits; Kafka carries balance snapshots.
- Provisioning, credit/debit, balance/history, transfers, full credit/debit refunds, daily/trusted/promotion rewards, outbox relay and projection, reconciliation, bounded SQL audit.
- Migrations V1–V4. V4 (`9ef2639`): indexed predecessor/tail integrity checks, pinned search paths, populated-data preflight, separate full audit; closed the quadratic commit cost and the TEMP-table shadow bypass.
- Transfer receipts filtered to a sender allowlist; JWT issuer/audiences required outside `local`; HTTP authorization matrix and signed-JWT tests (`760f4b0`).
- Test-quality findings F-04–F-08 closed (`e6472f7`, tests and `pom.xml` only): forced refund/provision races with exact loser codes, rejection matrices, retry rollback, real Kafka listener, adapter unit tests; PIT gate widened to `ApiProblems`, `RateLimitFilter`, `RedisRateLimiter`.
- F-01 (`5ffb0d2`, step 3 first slice): known transaction-start connection failures → structured 503 with same-key retry guidance; other transaction exceptions → sanitized 500 without retry header and class-only ERROR log. Real stopped-database/recovery regression added. [Behavior, classification limits and evidence](../database-outage-f01.md).
- CI (`79032c9`): GitHub Actions runs `clean verify -Pmutation` on Java 21 with Testcontainers. A hosted run has never been confirmed from this machine.

## Latest evidence (`5ffb0d2`, run 2026-09-14)

| Measure | Result |
| --- | --- |
| Full `spotless:apply clean verify -Pmutation` on Java 21 | 117 unit + 269 integration cases, zero failures/errors/skips; PIT 68/68; 12/12 SQL mutations; 81 s |
| Focused PIT (`ApiProblems`) | 25/25 killed, zero survivors/uncovered/timeouts/errors |
| Stopped-database HTTP regression | Fresh acquisition and same-key recovery pass with exact durable-state/replay checks |

Source: [F-01 evidence and commands](../database-outage-f01.md). Money-pressure, manual-mutation, JaCoCo and broader-PIT measurements were last taken at `6ba729d` ([2026-09-14 audit](../qa-audit-2026-09-14.md)) and are not remeasured for F-01; see [history](history.md#superseded-evidence).

## Open findings

| Id | Finding | Step |
| --- | --- | --- |
| F-02 high | One invalid Kafka event can stall the partitions handled by its consumer: single listener thread, unlimited retry, no durable quarantine. Reproduced 2026-09-14: poison at p0/o1 stalls later records at p0/o2 and p1/o1 | 4 |
| F-03 medium | Oversized sequence/balance wrap to `Long.MAX_VALUE`/0 and block later valid snapshots; schema-version coercion also confirmed. Authoritative wallet stays correct | 4 |
| F-09 / F-10 low | 405/415 map to `INVALID_INPUT`; 405 loses `Allow`. No JDBC socket read timeout; frozen-database probe exceeded 12 s (money stayed safe) | 3 / 6 |
| F-11 low | The configured PIT gate is deliberately bounded (domain classes plus five adapters); the broader all-class score is measured, not gated | 6 |
| Review N1 | V4 applies at application startup and blocks writers while it audits; run it as an explicit pre-deploy migration step | 6 |
| Review N3 | Commit triggers do not enforce per-operation sign/account semantics; inverse refunds are audit-only | later |
| Review F4 / F5 / F6 / F7 | Every `DataAccessException` maps to 503; promotion lock taken before duplicate/exhaustion checks; history rows lack refund origin and transfer counterparty; stored rejections and framework errors use inconsistent problem+json envelopes and generic 400 bodies name no field | 3 / 5 / 3 / 3 |

Closed findings and when they were verified: [history](history.md#closed-findings).

## Pending remediation

Steps 3–6 of the [ordered plan](../review-remediation-plan.md#ordered-implementation-plan); steps 1–2 are done and verified.

3. **Errors and audit fields (F-01 slice done).** Replace blanket `DataAccessException` → 503 with known-conflict 409, transient 503 and unexpected 500; cover statement/deferred-commit translation and observability; field-level 400 bodies; keep `Allow`; `originalTransactionId` and transfer counterparty in history; real rate-limit retry guidance.
4. **Messaging.** Wire `ledger.outbox.topic`; strict event DTO with long-range checks; `IllegalArgumentException` not retryable, bounded back-off, durable quarantine with replay; two-relay lease test; production replication and minimum ISR.
5. **Promotion contention.** Unlocked early rejection for already-claimed or exhausted campaigns; keep the locked authoritative check for winners.
6. **Production evidence.** Controlled V4 migration step; readiness semantics; container patch review (PostgreSQL 17.6 is behind 17.11); JDBC socket timeout; JaCoCo threshold in the pom; backup/restore and recovery drills; confirmed hosted CI; README refresh.

Verdict: core features implemented and verified; production readiness is **not** established. This queue does not authorize implementation; the user's current request does.

## Next action

Continue step 3 at [ApiProblems.database](../../src/main/java/com/example/walletledger/configuration/ApiProblems.java): replace blanket `DataAccessException` → 503 with known business-conflict, transient dependency and unexpected internal classifications, with real PostgreSQL statement/deferred-commit HTTP tests proving rollback and same-key recovery. Frozen existing connections (F-10) remain a separate timeout/recovery slice.
