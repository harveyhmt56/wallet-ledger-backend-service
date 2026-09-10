# Wallet Ledger Service

A Java 21 / Spring Boot **3.5.16** service for whole-unit game currency. PostgreSQL owns balances, immutable double-entry journals, idempotency, reward claims and the outbox. Redis limits requests; Kafka carries balance-change notifications.

## How to run, setup, database and tests

Prerequisites: Docker with Compose, Java 21 for host builds, and Python 3 for the demo. Maven 3.9.11 is downloaded by the checked-in wrapper with checksum verification. Container images have explicit versions: PostgreSQL 17.6, Redis 7.4.5, Kafka 3.9.1, and Temurin 21.0.8+9.

```sh
docker compose up --build -d
curl --fail http://localhost:8080/actuator/health
python3 scripts/demo.py
```

Compose binds published ports to localhost. The demo provisions Alice and Bob, credits and spends funds, transfers, claims daily/mission/promotion rewards, reverses a purchase, and reads history and reconciliation. Its stable idempotency keys make reruns safe. A first run on an empty database gives Alice 215 and Bob 20 units; daily rewards can change this on later dates.

| Local username | Password | Permission |
| --- | --- | --- |
| `service` | `service-password` | Provision, credit/debit, completion evidence, cancellation, authorized reads |
| `admin` | `admin-password` | Money operations, cancellation, reconciliation and metrics |
| `10000000-0000-0000-0000-000000000001` | `alice-password` | Alice's wallet, transfers and reward claims |
| `10000000-0000-0000-0000-000000000002` | `bob-password` | Bob's wallet, transfers and reward claims |

These fixed credentials exist only in the `local` profile. Other profiles use OAuth2 JWTs and refuse startup without a nonblank issuer and at least one nonblank audience: configure `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES`. Signed `roles` claims contain `PLAYER`, `SERVICE`, or `ADMIN`; player `sub` is the provisioned player UUID. Boot configures the decoder; optionally set `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` to avoid issuer discovery. Tests use a local JWK fixture and signed tokens through real HTTP/controllers; no live identity provider is contacted. Production provider integration remains deployment-specific. See [Spring Security JWT configuration](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).

Host development:

```sh
docker compose up -d postgres redis kafka
# On macOS, select Java 21 if necessary:
export JAVA_HOME=$(/usr/libexec/java_home -v21)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Configuration uses `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `MIGRATION_USERNAME`, `MIGRATION_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, and `KAFKA_BOOTSTRAP_SERVERS`. Local defaults are in `application-local.yml`. Provision separate database roles outside Compose for other environments. Flyway connects as `wallet_migration`; request processing uses `wallet_app`, which cannot rewrite or truncate journal history or create permanent objects in the public schema; database TEMP privilege remains available. `V1` creates the ledger, `V2` rewards, and `V3` the example consumer projection. `V4` audits existing data, replaces historical-prefix validation with indexed predecessor/tail checks, and hardens integrity functions. Plan a maintenance window for its write-blocking preflight; audit findings abort the upgrade. Startup Flyway is enabled by default. Production needs an enforced separate migration/validation step with writers quiesced before disabling startup Flyway on serving instances; the repository does not implement that deployment pipeline. See [V4 operation and evidence](docs/ledger-integrity-v4.md). Existing permanent databases must receive new versioned migrations, never edited applied migrations.

```sh
./mvnw test                         # fast unit and HTTP security adapter tests
./mvnw spotless:apply               # format Java
./mvnw clean verify                 # unit + real infrastructure tests + formatting + executable JAR
./mvnw -Pmutation verify            # complete gates and PIT domain mutation testing
./mvnw -Dtest=LoadMeasurement test   # explicit local measurement; target/load-report.json
./mvnw -Dtest=LedgerHistoryMeasurement test # warmed 1k/5k/10k/20k histories; target/ledger-history-v4.json
```

Tests create disposable containers and do not use the Compose database. Keep Docker running and permit its socket access. First builds need Maven Central and container registry access. Reports are in `target/surefire-reports`, `target/failsafe-reports`, and `target/pit-reports/index.html`. The executable artifact is `target/wallet-ledger-service-0.0.1-SNAPSHOT.jar`. If startup fails, inspect `docker compose ps` and `docker compose logs app`; check port conflicts, container health, and database role setup. `docker compose down` stops this stack and retains its data volumes.

Every mutation requires `Idempotency-Key` (1–200 characters). Monetary bodies use a positive integer `amount`, nonblank `reason` (max 500), `source` (max 100), and `reference` (max 200). Fractions, string-coerced numbers, zero, negative and overflowing amounts are rejected. Example after provisioning Alice:

```sh
curl --fail-with-body -u service:service-password \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: example-credit-1' \
  -d '{"amount":100,"reason":"Mission completed","source":"mission-server","reference":"mission-42"}' \
  http://localhost:8080/v1/wallets/10000000-0000-0000-0000-000000000001/credits
```

| API | Body / result |
| --- | --- |
| `POST /v1/players` | `playerId`; zero-balance wallet |
| `POST /v1/wallets/{playerId}/credits` or `/debits` | Monetary body; immutable receipt |
| `GET /v1/wallets/{playerId}/balance` | Current PostgreSQL balance and sequence |
| `GET /v1/wallets/{playerId}/transactions?limit=20&cursor=42` | `items`, `nextCursor`; limit 1–100 |
| `POST /v1/transfers` | Monetary body plus `recipientId`; sender from authentication, response contains only sender funds |
| `POST /v1/transactions/{transactionId}/refunds` | `reason`, `source`, `reference`; full reversal |
| `POST /v1/daily-login/claims` | No business payload; UTC daily reward |
| `POST /internal/v1/action-completions` | `playerId`, `rewardId`, `source`, `reference`; trusted service only |
| `POST /v1/rewards/{rewardId}/claims` | `completionReference` returned by the trusted completion endpoint |
| `POST /v1/promotions/{promotionId}/claims` | No business payload; limited reward |
| `GET /v1/admin/reconciliation` | Admin-only balance/ledger comparison |

The seeded mission ID is `20000000-0000-0000-0000-000000000001` (100 units, `mission-v1`). The seeded promotion is `30000000-0000-0000-0000-000000000001` (25 units, 100 distinct players, `promotion-v1`). Definitions are seeded for the assignment; there is no policy-management API.

Mutations return HTTP 200 receipts. Errors use `application/problem+json` with a stable `code`: 400 invalid input, 401/403 authentication/authorization, 404 missing resource, 409 business conflict, 429 request quota, and 503 for every `DataAccessException` handled by the current advice, including nontransient failures. Error classification remains a known gap; a 503 alone does not prove that the failure is transient. Replays include the original `balanceAfter`; use the balance endpoint for current funds. Transfer responses intentionally omit `recipientBalanceAfter` for fresh results and all replays, including receipts stored before this fix. They retain transaction identity, sender balance/sequence and recipient identity. Stored receipts and immutable journals are unchanged; see [privacy and authorization evidence](docs/api-security-step2.md).

## Design decisions and trade-offs

One modular application and one PostgreSQL database keep money movement atomic. `WalletService` is the shared posting boundary; reward services call it within the same transaction. Pure domain policies use checked arithmetic and injected time. JDBC makes SQL and lock behavior inspectable; it costs explicit mapping code.

Each journal has exactly two distinct accounts and equal opposite entries. Credits pair a player with platform issuance; debits pair a player with platform purchases; transfers pair two players. Refunds append inverse entries linked to an unchanged original. Deferred database triggers reject incomplete/unbalanced journals, mismatched player balances, broken sequences, and incorrect running balances. Ledger rows also have update/delete/truncate rejection triggers. Platform totals are derived, avoiding a global mutable platform-wallet lock. V4 validates new entries against indexed predecessors and the final wallet tail. Its separate full SQL audit checks history in a consistent snapshot; [run and schedule the bounded maintenance audit](docs/ledger-integrity-v4.md#populated-upgrades-and-operational-audit) and alert on any nonzero exit.

Money posting inserts one outbox event per affected player. A relay leases pending rows using `SKIP LOCKED`, publishes outside the money transaction, and marks delivery only after broker acknowledgement. Expired leases recover interrupted workers. Duplicate or reordered publication is expected: the example consumer stores event-ID deduplication with its local projection and accepts only newer wallet sequences using the event's absolute balance. It does not sum out-of-order deltas. See [Kafka delivery semantics](https://kafka.apache.org/39/design/design/).

Redis performs an atomic Lua counter with expiry. Its default limit is 120 authenticated requests per 60-second window, configured by `ledger.rate-limit.requests` and `ledger.rate-limit.window-seconds`. Redis failures fail open and increment `wallet.rate_limit.degraded`; PostgreSQL money protections remain authoritative. The atomic script approach follows [Redis scripting guarantees](https://redis.io/docs/latest/develop/programmability/eval-intro/).

Health probes are public; other Actuator endpoints require admin access. Logs carry a generated `correlationId`, also returned in `X-Correlation-ID`. Receipts and history carry transaction IDs. Metrics include command rejections/retries and outbox pending count, oldest age, delivery and failures. Reconciliation runs in a repeatable-read transaction.

## Concurrency & Idempotency

The outer command transaction reserves `(authenticated actor, key)`, checks a SHA-256 fingerprint of operation/target and recursively sorted JSON business content, executes the action, and stores its response before committing. Concurrent key copies coordinate through PostgreSQL uniqueness. Changed content returns `IDEMPOTENCY_KEY_REUSED`. A savepoint rolls back rejected business work while retaining its completed rejection response. Nested wallet/reward transactions also use savepoints, protecting direct service calls. Infrastructure failures roll back the reservation and all business writes. Selected transient database failures allow at most three attempts (two retries), each with a fresh transaction.

Lock order is idempotency reservation, business state/reference lock, then wallets in ascending UUID byte/text order. Wallet locks serialize conflicting debits and transfers use the same ordering in both directions. Immutable completion/refund identities use transaction advisory locks. Reference uniqueness is `(operation, source, reference)` across wallets; source systems must issue globally unique references within that scope. Entitlement constraints independently enforce one daily claim per player/date, one claim per completion, one player per promotion, and one reversal per original.

Connections use a five-second lock timeout and fifteen-second statement timeout. These and Spring's transaction timeout are not guaranteed deferred-commit deadlines. Real fault tests demonstrate PostgreSQL 17 `transaction_timeout` bounding deferred commit and same-key recovery; it is not globally enabled by V4. See [deadline evidence](docs/ledger-integrity-v4.md#observed-red--green). No network call runs inside money transactions. See [PostgreSQL explicit locks](https://www.postgresql.org/docs/17/explicit-locking.html), [deferred constraint triggers](https://www.postgresql.org/docs/17/sql-createtrigger.html), and [Spring nested transaction semantics](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html).

## Testing approach

Executable JUnit acceptance examples assert public API and database outcomes; Gherkin is not required by this repository. The original build notes report a red-green TDD process; the squashed implementation history cannot independently establish that sequence. Tests cover exact arithmetic, UTC streak boundaries, authorization, validation, lost-response replay, real HTTP requests to two application instances, 100 concurrent copies of one key, opposing transfers, concurrent refunds, stable cursor pages and ledger reconciliation.

The required debit race starts 100 independent operations of 10 against 500 and asserts exactly 50 successes, 50 rejections and balance zero. The quota race starts 500 players against 100 slots and checks exactly 100 distinct winners. Database tests explicitly hold a wallet lock, reject direct ledger corruption, and inject failures during outbox/claim persistence to prove atomic rollback. PostgreSQL is real; no H2 substitute is used. Kafka tests pause the real broker and replay delivery after a simulated acknowledgement/marking crash; Redis tests inspect the real counter TTL.

PIT targets the money, daily reward and sequence-projection domain policies, the transfer response projection and required JWT configuration checks with 80% mutation and coverage gates. Reports must contain evaluated mutants; zero-mutant and invalid runs are failures. JVM mutation does not mutate PostgreSQL triggers, so `LedgerSqlMutationIT` also runs 12 targeted SQL mutations against real PostgreSQL acceptance examples. See [PIT's Maven configuration](https://pitest.org/quickstart/maven/). Recorded results and measurement limits are in [build evidence](docs/build-evidence.md), [V4 evidence](docs/ledger-integrity-v4.md) and [API security evidence](docs/api-security-step2.md).

## Assumptions & limitations

- Core features, V4's database repair and step 2 privacy/authorization are implemented and verified locally; production readiness remains unestablished. [Remaining release gates](docs/review-remediation-plan.md#ordered-implementation-plan) include error clarity, messaging and deployment evidence.
- History still omits refund-origin and transfer-counterparty fields. The [API security evidence](docs/api-security-step2.md) distinguishes route authorization denials from replayable business rejections and local JWT fixtures from production provider integration.
- Service code enforces operation/account/sign and inverse-refund semantics. Generic commit triggers do not enforce all of those relationships; `refund_inverse` is an audit check. See [V4's database boundary](docs/ledger-integrity-v4.md#database-boundary).
- One `COIN` currency in Java `long` / PostgreSQL `BIGINT`; maximum player balance is `Long.MAX_VALUE`.
- The server's UTC date defines daily claims. Consecutive days grant `10 × streak day`; missed days reset the streak.
- Trusted completion evidence determines eligibility and server definitions determine amounts. Clients cannot mint arbitrary currency.
- Only full credit/debit reversals are supported. Transfer/partial reversals are excluded. Overdrawing reversals fail; reversals do not restore entitlements or promotion slots.
- Idempotency, ledger and consumer deduplication records are retained for the project lifetime. Archival is future work.
- One database owns all wallets. Hot wallets and campaign rows serialize writes. This design does not implement cross-database transfers or high availability.
- The outbox relay permits at-least-once publication and reordering. Automatic topic creation currently requests replication 1 outside a local-only restriction, so `acks=all` can acknowledge a single copy; production replication/minimum ISR must be provisioned and verified. The example consumer is a balance snapshot projection, not an exactly-once delivery claim. Poison records need operator intervention; no dead-letter workflow is supplied.
- Compose is a reproducible local environment. Production identity, secrets, backups, broker replication and deployment controls must be supplied by the deployment environment.
- Boot 3.5.16 is retained as required. It is the final open-source release of the 3.5 line; see the [official release lifecycle notice](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/).
