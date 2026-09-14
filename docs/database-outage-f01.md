# F-01: transaction-start outage responses

Implemented on 2026-09-14 in the working tree based on `6ba729d`, branch `coder/recovery-db-outage`. This is the next-action slice of remediation step 3; other step 3 findings remain pending.

## Behavior and boundary

[`ApiProblems`](../src/main/java/com/example/walletledger/configuration/ApiProblems.java) now handles `TransactionException`. A `CannotCreateTransactionException` with a connection-unavailable cause returns `503 DEPENDENCY_UNAVAILABLE`, `application/problem+json`, `Retry-After: 1`, and guidance to retry with the same idempotency key. Recognized causes are `SQLTransientConnectionException`, JDBC SQLState class `08`, and PostgreSQL `57P01`/`57P02`/`57P03` (shutdown or not accepting connections).

Other transaction exceptions return sanitized `500 INTERNAL_ERROR` without `Retry-After`, including unknown setup failures, unsupported transaction usage, unexpected rollback, and `TransactionSystemException` at commit. The handler logs only the exception class at ERROR; the existing correlation filter and structured logging retain request correlation. It does not log exception messages, SQL or connection details. HTTP 500 does not assert whether an uncertain commit persisted; callers must preserve the idempotency key when recovering.

The existing blanket `DataAccessException` → 503 mapping is unchanged and remains an open step 3 finding. Hikari's typed acquisition timeout signals pool unavailability even when its nested cause is a configuration/authentication fault; this classifier does not diagnose every root cause. No transaction execution, retry loop, schema, dependency, pool timeout or money logic changed. Frozen existing connections (F-10) still need their own timeout/recovery work.

## Fresh verification

All commands used Java 21.0.8, the checked-in Maven wrapper, and disposable Testcontainers services. Local Compose services were not test fixtures.

| Check | Observed result |
| --- | --- |
| Baseline `ApiProblemsTest` | 6 passed |
| Red advice matrix before production changes | 26 cases: 6 existing pass, 20 new test-body errors because transaction exceptions escaped MVC advice; no setup/discovery failure |
| Red stopped-PostgreSQL HTTP test | 1 assertion failure: expected 503, received 500. Hikari logged SQLState `57P01` during `getTransactionIsolation`; restart cleanup completed |
| Green advice matrix | 26 passed, zero failures/errors/skips |
| Green stopped-PostgreSQL HTTP/recovery test | 1 passed, zero failures/errors/skips; two requests during outage, including fresh acquisition after test-only pool eviction |
| Focused PIT for `ApiProblems` | 25/25 killed; 34/34 lines; zero survivors/uncovered/timeouts/errors, XML inspected |
| Complete `spotless:apply clean verify -Pmutation` | 117 unit + 269 integration cases, zero failures/errors/skips; PIT 68/68 killed (including 25/25 for `ApiProblems`), 12/12 SQL mutations detected; 81 s |

[`ApiProblemsTest`](../src/test/java/com/example/walletledger/configuration/ApiProblemsTest.java) checks the response status, code, safe detail, content type, retry header, cause-chain traversal and sanitized ERROR logging. Negative cases include null/unknown SQLState, SQL syntax/authentication/cancellation failures and commit wrappers containing connection failures.

[`DatabaseOutageHttpIT`](../src/test/java/com/example/walletledger/configuration/DatabaseOutageHttpIT.java) provisions and credits 100, then stops its own PostgreSQL container. Both unavailable debit attempts use the same key and must return the complete 503 contract. It restarts that container in `finally` with unchanged port bindings. Exact snapshots of wallets, journals, both ledger legs, idempotency and outbox rows remain unchanged after the outage and after replaying the earlier credit. Retrying the debit of 30 succeeds once: balance 70, sequence 2, two journals, four ledger entries, two outbox events and completed idempotency status. Replaying it leaves the recovered snapshot unchanged; the database integrity audit reports no findings.

Reproduction (run with Java 21 and Docker available):

```sh
./mvnw -B -ntp -Dtest=ApiProblemsTest test
./mvnw -B -ntp test-compile failsafe:integration-test failsafe:verify -Dit.test=DatabaseOutageHttpIT
./mvnw -B -ntp -Pmutation -DtargetClasses=com.example.walletledger.configuration.ApiProblems org.pitest:pitest-maven:mutationCoverage
./mvnw -B -ntp spotless:apply clean verify -Pmutation
```

Reports: `target/surefire-reports`, `target/failsafe-reports`, `target/pit-reports`; final XML totals and mutation statuses were inspected. No Testcontainers-labelled containers remained after completion. Session logs are `/private/tmp/wallet-f01-{baseline-unit,red-unit,red-http,green-unit,green-http,focused-pit,final-gate}.log`; they are ephemeral and not committed. These tests establish the stated local contracts, not production readiness, hosted CI or universal outage deadlines. JaCoCo and broader all-class PIT were not remeasured for this slice.

## Primary-source cross-check

- [Spring 6.2.19 transaction manager source](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.19/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java): `doBegin` wraps acquisition/preparation errors as `CannotCreateTransactionException`, including non-connection failures. It cannot be classified solely by that wrapper.
- [HikariCP 6.3.3 source](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-6.3.3/src/main/java/com/zaxxer/hikari/pool/HikariPool.java) and [Java 21 JDBC contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/SQLTransientConnectionException.html): acquisition timeouts use the typed connection exception while retaining their underlying cause.
- [PostgreSQL 17 SQLState definitions](https://www.postgresql.org/docs/17/errcodes-appendix.html): class `08` identifies connection exceptions; the three recognized `57P0x` values identify shutdown or inability to connect. Other operator-intervention codes are not treated as outage evidence by this handler.
