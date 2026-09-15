# Implementation map

Checked 2026-09-15 against `9b9ccd9` plus F-02 working-tree changes on `coder/mq-issue-fix`; V5 added, V1–V4 unchanged. [Index](../../MEMORY.md).
Read [open findings and pending steps](state.md#open-findings) alongside this map before making safety/readiness claims.

## Entry points

Paths below are the canonical sources; use the named method/class to locate behavior rather than trusting old line numbers.

| Concern | Source / entry point |
| --- | --- |
| HTTP wallet operations, owner/role checks, request constraints | [WalletController](../../src/main/java/com/example/walletledger/wallet/api/WalletController.java) |
| Provision, credit/debit, transfer, refund, history, reconciliation, shared `post` | [WalletService](../../src/main/java/com/example/walletledger/wallet/application/WalletService.java) |
| Persistent reservation, fingerprint, replay, rejection savepoint, retry | [CommandExecutor.execute](../../src/main/java/com/example/walletledger/idempotency/CommandExecutor.java) |
| Reward routes / transaction orchestration / SQL | [RewardController](../../src/main/java/com/example/walletledger/rewards/api/RewardController.java), [RewardService](../../src/main/java/com/example/walletledger/rewards/application/RewardService.java), [RewardRepository](../../src/main/java/com/example/walletledger/rewards/infrastructure/RewardRepository.java) |
| Local Basic auth vs nonlocal JWT, roles, Actuator access | [SecurityConfiguration](../../src/main/java/com/example/walletledger/configuration/SecurityConfiguration.java) |
| Strict JSON and UTC clock / HTTP errors | [CoreConfiguration](../../src/main/java/com/example/walletledger/configuration/CoreConfiguration.java), [ApiProblems](../../src/main/java/com/example/walletledger/configuration/ApiProblems.java) |
| Redis quota / filter | [RedisRateLimiter](../../src/main/java/com/example/walletledger/configuration/RedisRateLimiter.java), [RateLimitFilter](../../src/main/java/com/example/walletledger/configuration/RateLimitFilter.java) |
| Outbox claim/send/ack / topic and listener / deduplication | [OutboxRelay](../../src/main/java/com/example/walletledger/messaging/outbox/OutboxRelay.java), [MessagingConfiguration](../../src/main/java/com/example/walletledger/messaging/kafka/MessagingConfiguration.java), [BalanceProjection](../../src/main/java/com/example/walletledger/messaging/kafka/BalanceProjection.java) |

The root package is `com.example.walletledger`. SQL is mainly inside `WalletService` and `RewardRepository`; the original proposed `players` and `wallet.infrastructure` packages do not exist.

## Posting, locks and idempotency

- HTTP mutations call `CommandExecutor`: `REQUIRES_NEW`, `READ_COMMITTED`, configured 30-second transaction timeout. It reserves `(actor, request_key)`, compares SHA-256 over operation/target plus recursively key-sorted JSON, then executes and stores status/response before commit.
- Same key/content replays the stored receipt or rejection; changed content returns `409 IDEMPOTENCY_KEY_REUSED`. Replay retains the original `balanceAfter`; daily claims also need a new key for a new date because their fingerprint has no date payload.
- A command savepoint rolls back `BusinessException` work while allowing its rejection response to commit. Wallet/reward mutations use `NESTED` with `rollbackFor = Exception.class`; infrastructure failures roll back the outer command. `TransientDataAccessException` permits at most three attempts, each in a fresh transaction.
- Lock hierarchy: idempotency reservation → business state/advisory reference locks → wallets sorted by UUID **text** (PostgreSQL byte order, not Java `UUID.compareTo`). Streak/campaign rows use `FOR UPDATE`; immutable completion and refund identities use transaction advisory locks.
- Business reference uniqueness is `(operation, source, reference)` across wallets. `post` writes journal, two entries, player balances/sequences and outbox events; transfer makes two player events. Reward credits reuse this boundary.
- Hikari sets 5-second lock and 15-second statement timeouts. Fresh [deadline fault tests](../../src/test/java/com/example/walletledger/wallet/TransactionDeadlineIT.java) prove statement timeout does not bound deferred COMMIT, while PostgreSQL 17 transaction timeout does. The latter remains a per-deployment option; same-key rollback/retry and lost-acknowledgement replay are tested.

Spring's [nested propagation documentation](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html) corroborates the JDBC savepoint model; code determines this project's actual use.

## Schema and reads

| Migration | Contents |
| --- | --- |
| [V1__ledger.sql](../../src/main/resources/db/migration/V1__ledger.sql) | `player`, `ledger_account`, `wallet`, `journal_transaction`, `ledger_entry`, `idempotency_request`, `outbox_event`; constraints, immutable history triggers and deferred journal/wallet checks |
| [V2__rewards.sql](../../src/main/resources/db/migration/V2__rewards.sql) | `reward_definition`, `action_completion`, `reward_claim`, `daily_streak`, `daily_claim`, `promotion`, `promotion_claim`; seed policies and grants |
| [V3__messaging.sql](../../src/main/resources/db/migration/V3__messaging.sql) | `consumed_event`, `wallet_projection` |
| [V4__indexed_ledger_integrity.sql](../../src/main/resources/db/migration/V4__indexed_ledger_integrity.sql) | Write-blocking populated-schema audit, indexed predecessor/final-tail validation, hardened invoker functions and separate full SQL audit; V1–V3 unchanged |
| [V5__kafka_quarantine.sql](../../src/main/resources/db/migration/V5__kafka_quarantine.sql) | Quarantine keyed by consumer group/topic/partition/offset, UTF-8 payload/key bytes and error metadata; restricted operator replay intent audit |

- Provisioned `wallet_id` equals player UUID; account UUID is separate. Migration and request roles are separated by [role bootstrap](../../docker/postgres/01-roles.sql) and grants; runtime cannot update/delete/truncate journal history. V4 qualifies persistent relation/composite-type references and pins all integrity/audit functions to `pg_catalog, public, pg_temp` with invoker privileges. TEMP-shadow regressions pass with TEMP privileges retained.
- Deferred checks cover complete journals, ownership, contiguous sequences and running balances. V4 checks each new player entry's predecessor and the final stored wallet against its tail through the existing unique sequence index; multiple postings in one transaction are supported. The preflight establishes a valid immutable base before incremental checks replace V1's quadratic scans. See [V4 design/evidence](../ledger-integrity-v4.md) and PostgreSQL [constraint-trigger timing](https://www.postgresql.org/docs/17/sql-createtrigger.html).
- Generic commit checks do not enforce CREDIT/DEBIT/TRANSFER account-kind/sign combinations. The full audit detects inverse-refund violations, but no commit trigger enforces that relationship. `WalletService` constructs the intended postings; see [current boundary assessment](../review-remediation-plan.md#second-pass-fact-check--2026-09-10).
- Balance reads use PostgreSQL. History uses descending wallet sequence, exclusive `< cursor`, default limit 20, range 1–100, `items`/`nextCursor`. Current history lacks refund-origin and transfer-counterparty fields.
- `GET /v1/admin/reconciliation` remains a read-only `REPEATABLE_READ` aggregate balance comparison. `public.audit_ledger_integrity()` separately audits ownership, metadata, sequence/running balances, wallet totals, journals and inverse refunds in one snapshot. [Maintenance script](../../scripts/audit-ledger.sql) bounds execution and fails on findings; [deployment scheduling/alerts](../ledger-integrity-v4.md#populated-upgrades-and-operational-audit) require the operator's job runner.

## HTTP and reward boundaries

- [README API table](../../README.md#how-to-run-setup-database-and-tests) owns route/body/demo details. Mutations return 200 on success and require a nonblank `Idempotency-Key` of at most 200 characters. Strict JSON rejects fractions, scalar coercion and unknown properties.
- SERVICE/ADMIN: provision, credit, debit, refund and authorized reads. PLAYER: own reads, transfer from authenticated subject, daily/reward/promotion claims. Completion evidence is SERVICE-only; reconciliation is ADMIN-only.
- Local credentials are demo-only; nonlocal configuration uses JWT `roles` and subject. Nonlocal startup requires a nonblank issuer and nonempty, nonblank audiences. Boot still configures the decoder. Real HTTP tests verify signed tokens with a loopback JWK fixture; production provider integration remains external.
- Daily state, trusted completion claims and promotion capacity commit with the credit; applied policy versions are stored. Promotion validates player existence/active status before locking the campaign, then checks campaign enabled/duplicate/exhaustion state. See [requirements](requirements.md) for policy scope.
- `ApiProblems` maps known transaction-start connection failures to 503 with same-key retry guidance; other `TransactionException`s become safe `500 INTERNAL_ERROR` without a retry header. The ERROR log contains only the exception class; existing structured logging carries correlation. [F-01 classification and tests](../database-outage-f01.md). Every handled `DataAccessException` still maps to 503, and generic validation still omits field details.
- Transfer HTTP success responses use an explicit [sender field allowlist](../../src/main/java/com/example/walletledger/wallet/api/TransferReceipt.java) after command execution/replay, filtering recipient funds from new and legacy receipts without altering stored responses. Errors pass through unchanged; [step 2 evidence](../api-security-step2.md).

## Messaging, Redis and runtime

- Balance event v1: `eventId`, `walletId`, `walletSequence`, `journalTransactionId`, `delta`, `balanceAfter`, `reason`, `occurredAt`, `schemaVersion`. Topic/key: `wallet.balance-changed.v1` / wallet UUID.
- Relay leases up to 100 rows for 60 seconds using `SKIP LOCKED`; sends outside money transactions; marks delivery after Kafka acknowledgement with lease-token fencing. Duplicates/reordering remain possible. The configured `ledger.outbox.topic` is not wired into the hardcoded topic constant.
- Consumer commits event-ID deduplication and projection together; only newer sequences replace the absolute balance. It never sums out-of-order deltas. F-02: `IllegalArgumentException` and default fatal failures quarantine immediately; other failures get three attempts with 1-second back-off, even when exception types change. [KafkaQuarantine](../../src/main/java/com/example/walletledger/messaging/kafka/KafkaQuarantine.java) commits in `REQUIRES_NEW` before recovery returns; write/commit failures escape, retaining source delivery. Duplicate recovery preserves the first row. [Metrics, restricted replay and evidence](../kafka-quarantine-f02.md). Topic creation still uses three partitions and replication one outside a local-only restriction.
- Redis Lua counter/expiry defaults to 120 authenticated requests per 60 seconds; Actuator bypasses rate limiting. Redis failures fail open and increment `wallet.rate_limit.degraded`. HTTP `Retry-After` is currently hardcoded to 60.
- Startup Flyway is enabled with a separate migration datasource; it does not inherit Hikari init timeouts. Only pending migrations run. Production must enforce a controlled migration/validation step before disabling startup Flyway on serving instances; see the [upgrade runbook](../ledger-integrity-v4.md#populated-upgrades-and-operational-audit).
- [application.yml](../../src/main/resources/application.yml) owns timeouts, metrics, health probes and ECS logging; [local profile](../../src/main/resources/application-local.yml) changes demo passwords/logging. Correlation IDs come from [CorrelationFilter](../../src/main/java/com/example/walletledger/configuration/CorrelationFilter.java).
- [pom.xml](../../pom.xml) pins Java 21 / Boot 3.5.16. [Wrapper](../../.mvn/wrapper/maven-wrapper.properties): Maven 3.9.11. [Compose](../../compose.yaml)/[Dockerfile](../../Dockerfile): PostgreSQL 17.6, Redis 7.4.5, Kafka 3.9.1, Temurin 21.0.8+9. These are checked-in pins, not claims of current patch suitability or production readiness.
