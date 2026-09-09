# Build and verification evidence

Implementation date: 2026-09-08 (Hong Kong). Required runtime: Java 21, Spring Boot 3.5.16.

The baseline contained only the original README and an untracked user-owned `MEMORY.md`. No prior application tests or build existed. Work is on `codex/implement-wallet-ledger`; the memory file is preserved separately.

## TDD observations

Observed missing-behavior failures before implementation included:

- Crediting ten units returned the stub's zero balance.
- Provisioning returned an empty map instead of a zero-balance wallet.
- HTTP provisioning returned 404 before routes existed.
- Repeating one idempotency key invoked the action again.
- A new wallet sequence failed to advance the example projection.
- Rate limiting admitted the third request over a two-request quota and omitted its degraded metric.
- Fractional JSON input was accepted before strict deserialization was configured.
- The outbox relay returned zero deliveries despite a pending event and a real Kafka broker.
- Oversized source metadata consumed an idempotency key before validation was moved to the HTTP boundary.

Real integration verification additionally exposed JSON numeric normalization on replay and an immutable completion table's row-lock permission mismatch. The implementation normalizes persisted receipts consistently and uses transaction advisory locks for immutable completion identities.

## Required gates

Run `./mvnw spotless:apply clean verify -Pmutation` with Java 21 and Docker. The final Surefire, Failsafe and PIT reports under `target/` are the machine-readable evidence. The completion response records the final counts after report inspection.

Verified results: **15 unit/adapter tests and 32 integration tests**, zero failures, errors or skips. **19 evaluated PIT mutants, all 19 killed (100%)**, zero survivors, uncovered, timed-out or errored mutants. Mutated classes: `MoneyRules` (10), `DailyRewardPolicy` (4), `ProjectionPolicy` (3), and `BusinessException` (2). Line coverage of mutated classes was 27/30 (90%). Production packaging and Spotless also passed.

The same full pipeline passed from a fresh export of the staged source. The Docker image built successfully (`sha256:50c11e8085e4d8d5bf40ecc0dfd5cb55a43b9e7fc314c3e2693118f9b9b2068f`), and `docker compose up -d --wait` completed. The health endpoint returned `UP`. The demo ended with Alice at 215 units/sequence 7 and Bob at 20 units/sequence 1; reconciliation reported no mismatches. All eight outbox events were delivered and consumed, and both Kafka-backed projection balances matched PostgreSQL wallets.

Coverage includes the exact 100-debit/500-unit race; 100 concurrent copies of one key; 500 eligible players competing for 100 slots; opposing transfers; concurrent full refunds; an explicitly held wallet lock; immutable and incomplete journal safeguards; injected outbox/claim persistence failures; UTC midnight/streak behavior; real HTTP traffic across two application instances; real Redis TTLs; and a paused Kafka broker followed by duplicate delivery.

The JWT adapter test uses a fake decoder. It verifies role/subject translation and HTTP rejection, not communication with a production identity provider. Database, Redis and Kafka integration tests use actual disposable local containers.

The Docker build initially failed because the JDK image lacked `unzip`: Maven Wrapper switched from ZIP to a tarball whose checksum differed. Adding ZIP extraction preserves the configured ZIP checksum. The ZIP SHA-256 is `0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb`, and its SHA-512 was cross-checked against Maven Central.

## GitHub Actions verification

[Maven verification](../.github/workflows/verify.yml) runs on pushes, pull requests and manual dispatch using Java 21 (Temurin) on Ubuntu 24.04. It checks Docker access, then runs `./mvnw --batch-mode --no-transfer-progress clean verify -Pmutation`. This uses the same Maven gates above, with Spotless's bound `check` instead of `spotless:apply` so CI rejects formatting errors. Testcontainers creates the disposable PostgreSQL, Redis and Kafka dependencies; no Compose stack or repository secrets are required.

The workflow attempts to upload available Surefire, Failsafe and PIT reports even after failure as `verification-reports`, retained for 14 days. Setup failures may leave no reports. Configuration was cross-checked against the official [Ubuntu runner inventory](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md), [setup-java](https://github.com/actions/setup-java/tree/v5), [Testcontainers runtime requirements](https://java.testcontainers.org/supported_docker_environment/) and [artifact retention options](https://github.com/actions/upload-artifact/tree/v4#retention-period).

Local verification on 2026-09-09 against application baseline `14b1e8f` plus this CI change: the exact CI Maven command passed using Java 21.0.8 on ARM64 macOS with OrbStack Docker 29.4.0. Inspected XML reports show 15 unit/adapter and 32 integration tests, zero failures/errors/skips, and 19/19 killed PIT mutants with no other statuses. Packaging and Spotless passed; PIT HTML was present and reported 27/30 covered lines (90%) in the four targeted domain classes. Workflow validation passed with actionlint 1.7.12. These are local results; GitHub-hosted execution and artifact upload have not yet been exercised. No application behavior changed, so no application red-green cycle was introduced.

## Local load measurement

Measured at 2026-09-07T16:34:12Z with `./mvnw -Dtest=LoadMeasurement test`. Java 21.0.8 on an ARM64 macOS host, local PostgreSQL 17.6 under OrbStack; the Docker runtime reported about 4 GB memory. Each case starts 200 requests concurrently, using a 30-connection pool. The measured boundary includes application posting, persistent idempotency and PostgreSQL; it excludes HTTP, Redis and Kafka relay processing.

| Case | Operations/s | p50 ms | p95 ms | p99 ms | Samples observing lock waits | Maximum concurrent lock waiters |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 200 distinct wallets | 668.36 | 190.18 | 278.46 | 284.23 | 0 | 0 |
| One hot wallet | 183.64 | 349.23 | 978.20 | 1065.49 | 108 | 29 |

The monitor samples PostgreSQL lock waiters every 10 ms; these sample counts are not per-request lock-duration measurements. These short local runs show the serialization cost of one wallet. They establish no production throughput target or capacity guarantee, and do not model long histories, sustained traffic, network latency or infrastructure failures. Re-run the measurement in the intended environment before making a capacity decision.
