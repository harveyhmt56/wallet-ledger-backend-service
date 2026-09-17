# Project state

Checked 2026-09-17 at `6753811`. The money implementation baseline is `a3b7f9d`; `f321206` reorganized the README/docs, and `6753811` limited Actuator health component details to `ADMIN` and added an authorization regression. No fresh full application gate is recorded after `a3b7f9d`.

## Implemented facts

- Java 21 / Spring Boot 3.5.16 service. PostgreSQL owns balances, ledger history, entitlements, idempotency, and the outbox; Redis rate-limits; Kafka carries at-least-once balance snapshots.
- Provisioning, credit/debit, balance/history, transfer, full credit/debit reversal, daily/trusted/promotion rewards, outbox/projection, reconciliation, and the bounded SQL audit are implemented.
- Migrations V1–V5 provide ledger/reward/messaging schemas, indexed integrity checks, populated-data preflight, Kafka quarantine, and replay-intent audit.
- Transfer responses use a sender-safe allowlist. Local Basic auth and nonlocal JWT role/subject authorization are covered by endpoint tests. Health status is public, while component details are restricted to `ADMIN`.
- Exact event integers are validated before projection writes. Invalid events are durably quarantined; transient listener failures use bounded retry; projection updates and event deduplication commit together.

## Verified baseline

The 2026-09-16 full gate at `a3b7f9d` passed unit, integration, SQL-mutation, configured PIT, and combined coverage runs. A separate isolated black-box/chaos audit exercised normal operations, concurrency, duplicate requests, dependency stop/freeze, poison events, and process kill; authoritative money remained correct and reconciliation stayed consistent. These are historical results for that commit, not proof for a different checkout or production environment.

## Open findings

| ID | Current issue | Required outcome |
| --- | --- | --- |
| Review F4 | Every handled `DataAccessException` becomes retryable 503, including permanent SQL/data failures. | Classify SQLSTATE/cause: retryable dependency failures → 503, invalid data → useful 400, known conflicts → 409, unexpected invariant/programming failures → logged safe 500. |
| F-12 | A balance read can return 500 while writes return 503 during the dead pooled-connection window of a PostgreSQL outage. | Apply the same cause-aware classification to read-path transaction/data failures. |
| F-13 | Readiness contains only `readinessState`, so it can remain UP with PostgreSQL stopped. | Include database availability in readiness while keeping liveness independent; surface optional Redis/Kafka degradation deliberately. Spring Boot [does not add external checks by default](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes.external-state). |
| F-14 | Six counters are absent until first increment, so alert queries see no data after restart. | Register counters eagerly and verify zero-valued visibility. |
| F-09 | 405/415 are flattened to `INVALID_INPUT`; 405 loses `Allow`. | Preserve framework semantics and headers with consistent problem bodies. |
| F-10 | A request holding a JDBC connection during a frozen database has no client-side deadline. | Configure and fault-test a bounded socket/request deadline without weakening same-key recovery. |
| Messaging | `ledger.outbox.topic` is not wired; relay leases can expire mid-batch; automatic topic creation is replication one. | Use configured topic, prove two-relay lease safety, and provision/verify production replication, ISR, and acknowledgements. |
| Migration/deployment | V4 runs through startup Flyway and blocks writers during populated-data preflight. | Enforce the quiesced pre-deploy migration/audit procedure in [operations](../operations.md#populated-v4-migration). |
| History/API | Refund history lacks original transaction; transfer history lacks counterparty; generic 400s omit fields; rate-limit retry delay is fixed. | Add explicit audit fields and consistent actionable errors without exposing another player's funds. |
| Promotion | Campaign locking occurs before duplicate/exhaustion rejection. | Add safe read-only early rejection while retaining locked authoritative winner checks. |
| Production evidence | No confirmed hosted CI, live identity provider, multi-broker loss test, backup/restore drill, or deployment SLO evidence. PostgreSQL is pinned to 17.6 while [17.11 is the current supported 17.x minor](https://www.postgresql.org/support/versioning/). | Complete environment-specific release evidence and update tested patch pins after review. |

Generic ledger commit triggers also do not enforce every operation-specific sign/account rule; `WalletService` supplies those semantics and the audit detects inverse-refund violations. Treat that boundary as a known design limit.

## Next action

No application change is in progress. The highest-value code slice is to close Review F4 and F-12 together with an `ApiProblemsTest` classification matrix plus real invalid-data and read-during-outage integration cases. The user's current request must authorize any implementation.
