# Wallet Ledger Service

A **Java 21 / Spring Boot 3.5.16** backend for whole-unit game currency. Supports credits, debits, transfers, full refunds, balance/history queries, and daily, mission and limited-promotion rewards.

PostgreSQL owns money and its audit trail. Redis rate-limits requests; Kafka distributes balance-change events.

[Run locally](#run-locally) · [Run tests](#run-tests) · [Design](#design-decisions-and-trade-offs) · [Concurrency & idempotency](#concurrency--idempotency) · [Testing](#testing-approach) · [Limitations](#assumptions--limitations)

## Run locally

### 1. Start the application and database

Install **Docker with Compose** and **curl**, start Docker, then run these commands from the repository root. Java and Maven run inside the build container; no host JDK is needed for this path.

```sh
docker compose up --build -d
curl --fail --retry 30 --retry-connrefused --retry-delay 2 --max-time 5 \
  http://localhost:8080/actuator/health
```

Wait for a response containing `"status":"UP"`. The first build downloads dependencies and images. The [Compose configuration](compose.yaml) starts PostgreSQL, Redis, Kafka and the application; PostgreSQL creates the database and roles, and Flyway applies migrations automatically. No manual SQL setup is needed on a fresh volume.

The API is at `http://localhost:8080`. Published ports are localhost-only: `8080` (API), `5432` (PostgreSQL), `6379` (Redis), `9092` (Kafka). Database: `wallet_ledger`, application login: `wallet_app` / `wallet_app_local`.

### 2. Try the complete flow

With **Python 3** installed:

```sh
python3 scripts/demo.py
```

The demo creates Alice and Bob, exercises money/reward operations, then reads balances, history and reconciliation. On an empty database it ends with **Alice: 215, Bob: 20**. Reruns reuse idempotency keys; a later UTC date can add another daily reward.

The `local` profile uses demo Basic authentication. For example, after the demo:

```sh
curl --fail -u service:service-password \
  http://localhost:8080/v1/wallets/10000000-0000-0000-0000-000000000001/balance
```

See the [API reference](docs/api.md) for write examples, credentials and endpoints, or [development setup](docs/development.md) to run Spring Boot from your IDE/host.

### 3. Stop or troubleshoot

```sh
docker compose down              # stops the stack; keeps database and Kafka volumes
```

If startup fails, check `docker compose ps` and `docker compose logs --tail=100 app postgres redis kafka` for unhealthy dependencies or port conflicts. The curl command waits for HTTP health; Compose does not check application readiness.

## Run tests

Install **JDK 21** and set `JAVA_HOME` to it. The checked-in Maven wrapper downloads Maven 3.9.11; a separate Maven installation is unnecessary. On macOS, select Java with `export JAVA_HOME=$(/usr/libexec/java_home -v21)`.

```sh
./mvnw --version                 # confirm Java 21
./mvnw test                      # fast unit/HTTP adapter tests; no Docker needed
./mvnw clean verify              # unit + integration tests, format check, executable JAR
./mvnw clean verify -Pmutation   # same checks plus PIT mutation testing (CI command)
```

**Keep Docker running for `verify`.** Testcontainers starts disposable PostgreSQL, Redis and Kafka containers; the Compose stack does not need to be running, and its database is not used by tests. First runs require network access to download dependencies/images.

Results: `target/surefire-reports` (unit), `target/failsafe-reports` (integration), `target/pit-reports/index.html` (mutation). See [developer commands](docs/development.md#tests-formatting-and-reports) for formatting and optional measurements.

## Design decisions and trade-offs

**Use an immutable double-entry ledger so every balance change explains where the money came from or went.** Each journal has two equal, opposite entries: player/platform for credits and debits, or sender/recipient for transfers. Refunds append inverse entries linked to the original transaction; they never rewrite history.

| Decision | Benefit | Trade-off |
| --- | --- | --- |
| Ledger plus a stored wallet balance | Auditable history and fast balance reads | Extra storage and checks to keep both consistent |
| One PostgreSQL transaction per command | Balance, journal, reward state, receipt and outbox commit together | The database is the availability and scaling boundary |
| Row locks before checking funds | Conflicting debits cannot spend the same balance | Hot wallets and promotion rows serialize requests |
| Transactional outbox to Kafka | A committed payment retains its event even if Kafka is down | Delivery is asynchronous; duplicates and reordering must be handled |
| Spring JDBC with explicit SQL | Locking and transaction behavior are easy to inspect | More SQL and mapping code to maintain |

[WalletService](src/main/java/com/example/walletledger/wallet/application/WalletService.java) handles all postings. Database constraints/triggers enforce balanced journals, wallet totals, sequences and running balances, and reject changes to existing history. Platform totals are derived, avoiding a shared platform-balance lock. Some posting rules remain in service code; see [database checks and limits](docs/ledger-integrity-v4.md#database-boundary).

Balance reads use PostgreSQL. Redis only limits traffic and fails open on outage; it is not a source of funds. Kafka's consumer is an eventually consistent balance projection.

## Concurrency & idempotency

**Concurrency:** commands use PostgreSQL `READ COMMITTED` and `SELECT … FOR UPDATE` before checking funds. Transfers lock both wallets in a consistent UUID order; locks also protect reward capacity and claim/refund identities. This works across instances sharing the database. See [PostgreSQL row locks](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS).

**Idempotency:** every write requires an `Idempotency-Key` (1–200 nonblank characters). [CommandExecutor](src/main/java/com/example/walletledger/idempotency/CommandExecutor.java) reserves `(authenticated actor, key)` in PostgreSQL and fingerprints the operation, target and canonical request content.

- Same actor, key and content: return the stored result, including business rejections. Concurrent copies coordinate through a unique constraint.
- Same actor/key with different content: return `409 IDEMPOTENCY_KEY_REUSED`.
- Business rejection: a savepoint removes partial work, then the rejection is stored. A declined debit stays declined when replayed, even after adding funds.
- Failure before commit: the reservation and business writes roll back together. Selected transient database errors get at most three attempts, each in a new transaction.

**After a timeout or lost response, retry with the same key.** A replay's `balanceAfter` is the original receipt value; query `/balance` for current funds. Use a new key for a new action, including a new day's daily claim. Business references must also be unique across wallets within `(operation, source, reference)`; claim and refund constraints prevent duplicate business actions with different keys.

The outbox relay publishes after commit and marks delivery after Kafka acknowledgement; a crash between those steps can resend an event. The consumer deduplicates event IDs and applies only newer wallet sequences using absolute balances. Delivery is **at least once**, with [quarantine and operator replay](docs/kafka-quarantine-f02.md) for failed records. See [Kafka delivery semantics](https://kafka.apache.org/39/design/design/).

## Testing approach

Tests check financial state as well as responses:

- **Unit tests:** checked money arithmetic, reward/date policies, validation, authorization and adapters.
- **Real infrastructure tests:** PostgreSQL transactions, constraints and locks; Redis expiry/failure behavior; Kafka delivery, recovery and quarantine. No H2 substitute for money guarantees.
- **Fault and replay tests:** duplicate keys, lost responses, opposing transfers, concurrent refunds, limited rewards, persistence failures and rollback. PIT checks selected Java policies/adapters; targeted SQL mutations check database safeguards.

The key debit race in [MoneyPressureHttpIT](src/test/java/com/example/walletledger/wallet/MoneyPressureHttpIT.java) starts with **500 units** and sends **100 distinct debits of 10** through two application instances over real HTTP. It requires exactly **50 successes, 50 insufficient-funds rejections and balance 0**, then checks receipts, ledger entries, outbox payloads and history. Replaying successes and rejections after adding funds must leave financial rows unchanged.

[DatabaseSafeguardsIT](src/test/java/com/example/walletledger/wallet/DatabaseSafeguardsIT.java) makes the overlap deterministic: hold one debit uncommitted, observe the competing debit blocked by PostgreSQL, commit the first, and require the second to reject using the updated balance. Separate races cover 100 copies of one idempotency key and 500 players competing for 100 promotion slots.

The [dated QA report](docs/qa-audit-2026-09-16.md) records past results and remaining findings; the commands above produce fresh evidence for your checkout.

## Assumptions & limitations

- **Currency and policy:** one whole-unit `COIN` currency using Java `long` / PostgreSQL `BIGINT`; player balances cannot be negative or exceed `Long.MAX_VALUE`. Daily claims use server UTC. Reward amounts and eligibility are server-controlled; seeded policies have no management API.
- **Refund scope:** only full credit/debit reversals. No partial or transfer refunds; a reversal cannot overdraw a wallet and does not restore reward entitlements or promotion slots.
- **Distributed deployment:** multiple app instances can share PostgreSQL. Cross-database/sharded transfers need a transfer protocol, recovery/compensation and reconciliation; they are not implemented. Database failover and recovery still need deployment testing.
- **Event delivery:** consumers can lag; no end-to-end exactly-once guarantee. Topic creation currently requests replication factor 1, including outside local mode. Production needs broker replication/minimum ISR settings and stronger relay recovery evidence.
- **Retention and audit:** ledger, idempotency and consumer deduplication records are retained indefinitely; archival is future work. History lacks refund-origin and transfer-counterparty fields.
- **Known operational gaps:** database errors can receive misleading 503 retry guidance, balance reads can return 500 during an outage, readiness can remain UP with PostgreSQL down, and some metrics appear only after first use. End-to-end database request deadlines also need hardening.

**Production readiness is not established.** Compose provides a local environment; deployment still needs JWT identity integration, secrets, controlled migrations, backups/restore drills and high availability. See [open findings and improvements](docs/memory/state.md#open-findings) and the [migration runbook](docs/ledger-integrity-v4.md#populated-upgrades-and-operational-audit).
