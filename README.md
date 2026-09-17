# Wallet Ledger Service

This is a **Java 21 / Spring Boot 3.5.16** backend for whole-unit game currency. It supports credits, debits, transfers, full refunds, balance/history queries, and daily, mission and limited-promotion rewards.

PostgreSQL keeps track of money and its audit trail. Redis limits requests, and Kafka sends out balance-change events.

[Run locally](#run-locally) · [Run tests](#run-tests) · [Design](#design-decisions-and-trade-offs) · [Concurrency & idempotency](#concurrency--idempotency) · [Testing](#testing-approach) · [Limitations](#assumptions--limitations)

## Run locally

### 1. Start the application and database

You'll need **Docker with Compose** and **curl**. Start Docker, then run these commands from the repository root. Java and Maven run inside the build container, so you don't need a JDK installed on your machine for this setup.

```sh
docker compose up --build -d
curl --fail --retry 30 --retry-connrefused --retry-delay 2 --max-time 5 \
  http://localhost:8080/actuator/health
```

Wait until the response includes `"status":"UP"`. The first build downloads the dependencies and images. The [Compose configuration](compose.yaml) starts PostgreSQL, Redis, Kafka and the application. PostgreSQL creates the database and roles, and Flyway applies the migrations for you. On a fresh volume, there's no manual SQL setup to do.

You can reach the API at `http://localhost:8080`. Published ports are only available on localhost: `8080` (API), `5432` (PostgreSQL), `6379` (Redis), `9092` (Kafka). The database is `wallet_ledger`, and the application login is `wallet_app` / `wallet_app_local`.

### 2. Try the complete flow

Once you have **Python 3** installed, run:

```sh
python3 scripts/demo.py
```

The demo creates Alice and Bob, walks through money and reward operations, then reads their balances, history and reconciliation results. Starting from an empty database, it ends with **Alice: 215, Bob: 20**. Running it again reuses the same idempotency keys; running it on a later UTC date can add another daily reward.

The `local` profile uses Basic authentication with demo credentials. After running the demo, you can try:

```sh
curl --fail -u service:service-password \
  http://localhost:8080/v1/wallets/10000000-0000-0000-0000-000000000001/balance
```

The [API reference](docs/api.md) has write examples, credentials and endpoints. If you'd like to run Spring Boot from your IDE or directly on your machine, follow the [development setup](docs/development.md).

### 3. Stop or troubleshoot

```sh
docker compose down              # stops the stack; keeps database and Kafka volumes
```

If the app won't start, check `docker compose ps` and `docker compose logs --tail=100 app postgres redis kafka` for unhealthy dependencies or port conflicts. The curl command waits for HTTP health; Compose doesn't check whether the application is ready.

## Run tests

Install **JDK 21** and point `JAVA_HOME` to it. The Maven wrapper included in the repo downloads Maven 3.9.11, so you don't need to install Maven separately. On macOS, select Java with `export JAVA_HOME=$(/usr/libexec/java_home -v21)`.

```sh
./mvnw --version                 # confirm Java 21
./mvnw test                      # fast unit/HTTP adapter tests; no Docker needed
./mvnw clean verify              # unit + integration tests, format check, executable JAR
./mvnw clean verify -Pmutation   # same checks plus PIT mutation testing (CI command)
```

**Keep Docker running for `verify`.** Test-containers starts disposable PostgreSQL, Redis and Kafka containers. You don't need the Compose stack running, and tests don't use its database. The first run needs network access to download dependencies and images.

You'll find results in `target/surefire-reports` (unit), `target/failsafe-reports` (integration) and `target/pit-reports/index.html` (mutation). See [developer commands](docs/development.md#tests-formatting-and-reports) for formatting and optional measurements.

## Design decisions and trade-offs

**We use an immutable double-entry ledger so every balance change shows where the money came from or went.** Each journal has two equal, opposite entries: player/platform for credits and debits, or sender/recipient for transfers. Refunds add inverse entries linked to the original transaction, so the original history stays untouched.

| Decision | Benefit | Trade-off |
| --- | --- | --- |
| Ledger plus a stored wallet balance | A history you can audit, with fast balance reads | Extra storage and checks to keep both consistent |
| One PostgreSQL transaction per command | Balance, journal, reward state, receipt and outbox commit together | Availability and scaling depend on the database |
| Row locks before checking funds | Competing debits can't spend the same balance | Busy wallets and promotion rows process requests one at a time |
| Transactional outbox to Kafka | A committed payment keeps its event even when Kafka is down | Events arrive asynchronously, so duplicates and reordering need handling |
| Spring JDBC with explicit SQL | Locking and transaction behavior are easy to follow | More SQL and mapping code to maintain |

[WalletService](src/main/java/com/example/walletledger/wallet/application/WalletService.java) handles all postings. Database constraints and triggers enforce balanced journals, wallet totals, sequences and running balances, and reject changes to existing history. Platform totals are derived, so there's no shared platform-balance lock. Some posting rules still live in service code; see [the implementation map](docs/memory/implementation.md#schema-and-reads).

Balance reads come from PostgreSQL. Redis only limits traffic; if it goes down, requests are allowed through. It isn't a source of funds. The Kafka consumer keeps an eventually consistent projection of balances.

## Concurrency & idempotency

**Concurrency:** commands use PostgreSQL `READ COMMITTED` and `SELECT … FOR UPDATE` before checking funds. Transfers lock both wallets in the same UUID order. Locks also protect reward capacity and claim/refund identities. This works across application instances that share the database. See [PostgreSQL row locks](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS).

**Idempotency:** every write needs an `Idempotency-Key` (1–200 nonblank characters). [CommandExecutor](src/main/java/com/example/walletledger/idempotency/CommandExecutor.java) reserves `(authenticated actor, key)` in PostgreSQL and fingerprints the operation, target and canonical request content.

- Same actor, key and content: you get the stored result back, including business rejections. A unique constraint coordinates copies that arrive at the same time.
- Same actor/key with different content: you get `409 IDEMPOTENCY_KEY_REUSED`.
- Business rejection: a savepoint rolls back partial work, then the rejection is stored. Replaying a declined debit still returns that rejection, even after you've added funds.
- Failure before commit: the reservation and business writes roll back together. Selected transient database errors get at most three attempts, each in a new transaction.

**After a timeout or lost response, retry with the same key.** A replay's `balanceAfter` comes from the original receipt; use `/balance` to check current funds. Use a new key for a new action, including each new day's daily claim. Business references also need to be unique across wallets within `(operation, source, reference)`. Claim and refund constraints prevent the same business action from happening twice with different keys.

The outbox relay publishes after commit and marks an event as delivered after Kafka acknowledges it. A crash between those steps can cause the event to be sent again. The consumer deduplicates event IDs and applies only newer wallet sequences using absolute balances. Delivery is **at least once**, with [quarantine and operator replay](docs/operations.md#kafka-quarantine) for failed records. See [Kafka delivery semantics](https://kafka.apache.org/39/design/design/).

## Testing approach

Tests check what happened to the money as well as the response:

- **Unit tests:** cover checked money arithmetic, reward/date policies, validation, authorization and adapters.
- **Real infrastructure tests:** cover PostgreSQL transactions, constraints and locks; Redis expiry and failure behavior; and Kafka delivery, recovery and quarantine. Money guarantees are tested against PostgreSQL, without an H2 substitute.
- **Fault and replay tests:** cover duplicate keys, lost responses, opposing transfers, concurrent refunds, limited rewards, persistence failures and rollback. PIT checks selected Java policies and adapters; targeted SQL mutations check the database safeguards.

The main debit race in [MoneyPressureHttpIT](src/test/java/com/example/walletledger/wallet/MoneyPressureHttpIT.java) starts with **500 units** and sends **100 distinct debits of 10** through two application instances over real HTTP. The expected result is exactly **50 successes, 50 insufficient-funds rejections and balance 0**. The test then checks receipts, ledger entries, outbox payloads and history. After adding funds, replaying both successes and rejections must leave the financial rows unchanged.

[DatabaseSafeguardsIT](src/test/java/com/example/walletledger/wallet/DatabaseSafeguardsIT.java) makes sure the requests overlap: it holds one debit uncommitted, checks that PostgreSQL blocks the competing debit, then commits the first. The second must reject the debit using the updated balance. Separate races cover 100 copies of one idempotency key and 500 players competing for 100 promotion slots.

The [current state](docs/memory/state.md) records the latest verified baseline and remaining findings. Run the commands above to get fresh results for your checkout.

## Assumptions & limitations

- **Currency and policy:** there's one whole-unit `COIN` currency, using Java `long` / PostgreSQL `BIGINT`. Player balances can't be negative or exceed `Long.MAX_VALUE`. Daily claims use server UTC. The server controls reward amounts and eligibility; seeded policies don't have a management API.
- **Refund scope:** only full credit/debit reversals are supported. There are no partial or transfer refunds. A reversal can't overdraw a wallet, and it doesn't restore reward entitlements or promotion slots.
- **Distributed deployment:** multiple app instances can share PostgreSQL. Cross-database/sharded transfers aren't implemented; they'd need a transfer protocol, recovery/compensation and reconciliation. Database failover and recovery still need deployment testing.
- **Event delivery:** consumers can fall behind, and there's no end-to-end exactly-once guarantee. Topic creation currently requests replication factor 1, even outside local mode. Production still needs broker replication/minimum ISR settings and stronger evidence for relay recovery.
- **Retention and audit:** ledger, idempotency and consumer deduplication records are kept indefinitely. Archival is still future work. History doesn't include refund-origin or transfer-counterparty fields.
- **Known operational gaps:** database errors can give misleading 503 retry guidance, balance reads can return 500 during an outage, readiness can stay UP with PostgreSQL down, and some metrics only appear after their first use. End-to-end database request deadlines also need more work.

**This hasn't been proven ready for production.** Compose gives you a local environment. Deployment still needs JWT identity integration, secrets, controlled migrations, backups, restore drills and high availability. See [open findings and improvements](docs/memory/state.md#open-findings) and [production operations](docs/operations.md).

## License

[MIT](LICENSE).
