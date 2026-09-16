# Development and operations reference

Start with the [README quickstart](../README.md). This page covers host development, configuration and operational details. See the [API reference](api.md) for local users, request examples and production JWT configuration.

## Run the application on your host

Install Java 21 and Docker with Compose. Run these commands from the repository root:

```sh
docker compose stop app                         # free port 8080 if the Compose app is running
docker compose up -d --wait postgres redis kafka
java -version                                  # should report Java 21
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

On macOS, select an installed JDK 21 first with `export JAVA_HOME=$(/usr/libexec/java_home -v21)`. The checked-in Maven wrapper downloads Maven 3.9.11 and verifies its checksum; a separate Maven installation is unnecessary. First builds need access to Maven Central and container registries.

Compose waits for dependency health checks. The application has no Compose health check; when running it in Docker, wait for the HTTP health response as shown in the README before using the API. See [Docker startup ordering](https://docs.docker.com/compose/how-tos/startup-order/) and [curl retries](https://curl.se/docs/manpage.html#--retry).

## Database and configuration

Compose creates `wallet_ledger` and initializes two roles through [01-roles.sql](../docker/postgres/01-roles.sql): `wallet_migration` owns the schema and runs Flyway; `wallet_app` serves requests. The app role can append journal entries and update wallet balances, but cannot rewrite/delete/truncate journal history or create permanent objects in `public`. TEMP privilege remains available. Other environments must provision their own roles and secrets.

The [base configuration](../src/main/resources/application.yml), [local overrides](../src/main/resources/application-local.yml) and [Compose file](../compose.yaml) define these values:

| Variable | Host with `local` profile | Compose app |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/wallet_ledger` | `jdbc:postgresql://postgres:5432/wallet_ledger` |
| `DB_USERNAME` | `wallet_app` | Same |
| `DB_PASSWORD` | `wallet_app_local` | Same |
| `MIGRATION_USERNAME` | `wallet_migration` | Same |
| `MIGRATION_PASSWORD` | `wallet_migration_local` | Same |
| `REDIS_HOST` | `localhost` | `redis` |
| `REDIS_PORT` | `6379` | Same |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:19092` |

Local database passwords are demonstration values. Outside `local`, database passwords must be supplied explicitly. Published Compose ports bind to `127.0.0.1`: PostgreSQL 5432, Redis 6379, Kafka 9092 and the app 8080. Kafka advertises separate host and container addresses; use the matching address above.

Flyway runs automatically at application startup. Applied permanent schemas must receive new versioned migrations; never edit an applied migration.

| Migration | Purpose |
| --- | --- |
| [V1](../src/main/resources/db/migration/V1__ledger.sql) | Wallets, ledger, idempotency and outbox |
| [V2](../src/main/resources/db/migration/V2__rewards.sql) | Reward definitions and claims |
| [V3](../src/main/resources/db/migration/V3__messaging.sql) | Consumer deduplication and balance projection |
| [V4](../src/main/resources/db/migration/V4__indexed_ledger_integrity.sql) | Existing-data audit and indexed ledger integrity checks |
| [V5](../src/main/resources/db/migration/V5__kafka_quarantine.sql) | Kafka quarantine and operator replay audit |

**For a populated V4 upgrade, schedule a maintenance window and quiesce writers.** Its full-history preflight blocks writes and aborts on inconsistent data. Production needs an enforced migrate/validate step before serving instances disable startup Flyway; that deployment pipeline is not implemented here. Follow the [upgrade procedure and bounded ledger audit](ledger-integrity-v4.md#populated-upgrades-and-operational-audit), including migration-session timeout settings. Request-pool timeouts do not bound Flyway's separate connection.

## Tests, formatting and reports

```sh
./mvnw test                         # unit and adapter tests; no Docker needed
./mvnw clean verify                 # tests, integration tests, formatting check and JAR
./mvnw clean verify -Pmutation      # also run PIT mutation and coverage gates
./mvnw spotless:apply               # format Java
./mvnw spotless:check               # check Java formatting without changing files
```

Java 21 is required. Integration tests use disposable PostgreSQL, Redis and Kafka containers; keep Docker running with accessible socket permissions. They do not use the Compose database, and the Compose stack need not be running. `verify` runs both Surefire (`*Test`) and Failsafe (`*IT`), following [Maven's lifecycle](https://maven.apache.org/surefire/maven-failsafe-plugin/). The [CI workflow](../.github/workflows/verify.yml) runs `clean verify -Pmutation`.

Reports appear in `target/surefire-reports/`, `target/failsafe-reports/` and `target/pit-reports/index.html`. The executable package is `target/wallet-ledger-service-0.0.1-SNAPSHOT.jar`. PIT requires at least 80% mutation and coverage scores for its configured targets and fails if there are no mutations; it is not whole-service coverage. See [pom.xml](../pom.xml) for the target list and [test navigation](memory/verification.md#test-navigation) for focused suites.

Optional local measurements also need Docker and are excluded from normal test discovery:

```sh
./mvnw -Dtest=LoadMeasurement test
./mvnw -Dtest=LedgerHistoryMeasurement test
```

Outputs are `target/load-report.json` and `target/ledger-history-v<schema-version>.json` (currently `v5`). They measure local Java/PostgreSQL behavior, not production HTTP latency or Kafka delivery. See [measurement scope and comparison](ledger-integrity-v4.md#long-history-measurements).

## Troubleshooting and observability

```sh
docker compose ps
docker compose logs --tail=100 app
docker compose logs --tail=100 postgres redis kafka
docker compose down                # stop the stack; retain database and Kafka volumes
```

For startup failures, check port conflicts, dependency health and database role credentials. [PostgreSQL initialization scripts](https://docs.docker.com/guides/postgresql/immediate-setup-and-data-persistence/) apply when its data directory is first created; a retained database volume keeps its existing roles/passwords. Do not delete volumes as a routine troubleshooting step.

`/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` are public. Other Actuator endpoints, including `/actuator/metrics`, require admin access. The current readiness probe does not include PostgreSQL availability; an `UP` result is not proof that money requests can succeed. See [known gaps](../README.md#assumptions--limitations).

Logs include a generated `correlationId`, returned to callers as `X-Correlation-ID`; receipts and history include transaction IDs. Monitor command rejection/retry counts, outbox pending count and oldest age, delivery failures, Redis fail-open events and Kafka quarantine failures. Some counters appear only after first use. Redis limits default to 120 authenticated requests per 60 seconds (`ledger.rate-limit.requests`, `ledger.rate-limit.window-seconds`).

Use the admin reconciliation endpoint for wallet/ledger comparison and schedule the [bounded SQL ledger audit](ledger-integrity-v4.md#populated-upgrades-and-operational-audit). For incidents, follow the [database outage and same-key retry guidance](database-outage-f01.md) and [Kafka quarantine triage and audited replay procedure](kafka-quarantine-f02.md#detection-and-triage).
