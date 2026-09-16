# Project state

Fresh note: replace facts here whenever the baseline moves. Checked 2026-09-16 at `863d63f` plus verified F-03 working-tree changes on `coder/corrupt-projection`. V1–V5 unchanged by F-03. [Index](../../MEMORY.md) · [history](history.md).

## Implemented

- One Spring JDBC application, Java 21 / Boot 3.5.16. PostgreSQL owns money, claims, idempotency and outbox; Redis rate-limits; Kafka carries balance snapshots.
- Provisioning, credit/debit, balance/history, transfers, full credit/debit refunds, daily/trusted/promotion rewards, outbox relay and projection, reconciliation, bounded SQL audit.
- Migrations V1–V5. V4 (`9ef2639`): indexed predecessor/tail integrity checks, pinned search paths, populated-data preflight, separate full audit; closed the quadratic commit cost and the TEMP-table shadow bypass.
- Transfer receipts filtered to a sender allowlist; JWT issuer/audiences required outside `local`; HTTP authorization matrix and signed-JWT tests (`760f4b0`).
- Test-quality findings F-04–F-08 closed (`e6472f7`, tests and `pom.xml` only): forced refund/provision races with exact loser codes, rejection matrices, retry rollback, real Kafka listener, adapter unit tests; PIT gate widened to `ApiProblems`, `RateLimitFilter`, `RedisRateLimiter`.
- F-01 (`5ffb0d2`, step 3 first slice): known transaction-start connection failures → structured 503 with same-key retry guidance; other transaction exceptions → sanitized 500 without retry header and class-only ERROR log. Real stopped-database/recovery regression added. [Behavior, classification limits and evidence](../database-outage-f01.md).
- F-02 (`863d63f`, step 4 recovery slice): invalid events quarantine immediately; other listener failures get three attempts with bounded back-off even when exception types change. V5 quarantine commits before source acknowledgement; recovery failure retains delivery; restart deduplicates evidence. Restricted replay audit, authoritative outbox replay procedure and alert metrics added. [Behavior and evidence](../kafka-quarantine-f02.md).
- F-03 (working tree, step 4 validation slice): `schemaVersion`, `walletSequence` and `balanceAfter` require exact JSON integer types and representable Java longs before conversion/writes; schema 1, positive sequence and nonnegative balance remain required. Real listener regressions verify quarantine and following valid same-ID snapshots; PIT now includes `BalanceProjection`. [Behavior, red/green and limits](../balance-projection-f03.md).
- CI (`79032c9`): GitHub Actions runs `clean verify -Pmutation` on Java 21 with Testcontainers. A hosted run has never been confirmed from this machine.

## Latest evidence (`863d63f` + F-03 working tree, run 2026-09-16)

| Measure | Result |
| --- | --- |
| Full `spotless:apply clean verify -Pmutation` on Java 21 | 171 unit + 277 integration cases, zero failures/errors/skips; PIT 92/94 killed, two uncovered unchanged factory methods; 12/12 SQL mutations; 113 s |
| Focused parser/Kafka checks | 91 unit + 16 real integration cases pass (9 listener, 7 messaging); `BalanceProjection` PIT 10/10 killed, 39/39 lines; zero survivors/uncovered/timeouts/errors |
| F-03 regression | All three wrapping fields quarantine immediately; prior projection/dedup stay unchanged; following valid same-ID snapshot updates projection; wallet/ledger/outbox unchanged |

Source: [F-03 evidence and commands](../balance-projection-f03.md#fresh-verification-evidence). The full gate includes existing F-02 recovery/replay, money-pressure and stopped-database tests. Additional manual mutations, JaCoCo and broader all-class PIT were not remeasured for F-03; see [historical evidence](history.md#superseded-evidence).

## Open findings

| Id | Finding | Step |
| --- | --- | --- |
| F-09 / F-10 low | 405/415 map to `INVALID_INPUT`; 405 loses `Allow`. No JDBC socket read timeout; frozen-database probe exceeded 12 s (money stayed safe) | 3 / 6 |
| F-11 low | PIT remains deliberately bounded (domain plus eight adapters/configurations); broader all-class score is measured, not gated. Two unchanged Kafka factory methods are uncovered by unit mutation tests | 6 |
| Review N1 | V4 applies at application startup and blocks writers while it audits; run it as an explicit pre-deploy migration step | 6 |
| Review N3 | Commit triggers do not enforce per-operation sign/account semantics; inverse refunds are audit-only | later |
| Review F4 / F5 / F6 / F7 | Every `DataAccessException` maps to 503; promotion lock taken before duplicate/exhaustion checks; history rows lack refund origin and transfer counterparty; stored rejections and framework errors use inconsistent problem+json envelopes and generic 400 bodies name no field | 3 / 5 / 3 / 3 |

Closed findings and when they were verified: [history](history.md#closed-findings).

## Pending remediation

Steps 3–6 of the [ordered plan](../review-remediation-plan.md#ordered-implementation-plan); steps 1–2 are done and verified.

3. **Errors and audit fields (F-01 slice done).** Replace blanket `DataAccessException` → 503 with known-conflict 409, transient 503 and unexpected 500; cover statement/deferred-commit translation and observability; field-level 400 bodies; keep `Allow`; `originalTransactionId` and transfer counterparty in history; real rate-limit retry guidance.
4. **Messaging (F-02 recovery and F-03 numeric validation done).** Wire `ledger.outbox.topic`; two-relay lease test; production replication and minimum ISR. Deploy the documented quarantine alerts/operator privileges.
5. **Promotion contention.** Unlocked early rejection for already-claimed or exhausted campaigns; keep the locked authoritative check for winners.
6. **Production evidence.** Controlled V4 migration step; readiness semantics; container patch review (PostgreSQL 17.6 is behind 17.11); JDBC socket timeout; JaCoCo threshold in the pom; backup/restore and recovery drills; confirmed hosted CI; README refresh.

Verdict: core features implemented and verified; production readiness is **not** established. This queue does not authorize implementation; the user's current request does.

## Next action

The requested F-03 fix is implemented and verified, uncommitted on `coder/corrupt-projection`; [evidence](../balance-projection-f03.md). Topic configuration, competing relay leases and deployment durability remain pending. This note does not authorize the next slice or repair pre-existing corrupted projections.
