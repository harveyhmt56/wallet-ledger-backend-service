# F-03: exact balance event integers

Implemented on `coder/corrupt-projection` from clean baseline `863d63f` (F-02 committed). Verified 2026-09-16 with Java 21.0.8, Spring Boot 3.5.16 and disposable Testcontainers PostgreSQL 17.6 / Kafka 3.9.1. Existing F-02 work is preserved; no migration or dependency change is needed.

## Behavior and boundary

[`BalanceProjection.accept`](../src/main/java/com/example/walletledger/messaging/kafka/BalanceProjection.java) requires JSON integer tokens representable as Java `long` for all three numeric fields before calling JDBC:

| Field | Accepted values |
| --- | --- |
| `schemaVersion` | Exactly integer `1` |
| `walletSequence` | Integer `1` through `9223372036854775807` |
| `balanceAfter` | Integer `0` through `9223372036854775807` |

Missing/null values, strings, booleans, arrays/objects, decimals (including `1.0`), exponent forms (including `1e0`), unsupported versions, invalid signs and long overflow are rejected with `IllegalArgumentException` before deduplication or projection writes. The existing F-02 listener policy quarantines that exception immediately. Valid absolute snapshots retain the existing event-ID deduplication and newest-sequence transaction semantics.

The parser checks `isIntegralNumber()` and `canConvertToLong()` before `longValue()`. Jackson documents that the range check alone permits floating-point coercion, while its integer-type check distinguishes integer nodes. Java documents that narrowing an oversized `BigInteger` retains only the low 64 bits. Both checks are needed to prevent the observed wraps. Sources: [Jackson 2.21.4 JsonNode](https://javadoc.io/static/com.fasterxml.jackson.core/jackson-databind/2.21.4/com/fasterxml/jackson/databind/JsonNode.html#canConvertToLong()), [Java 21 BigInteger](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigInteger.html#longValue()).

This prevents newly received invalid events from corrupting the projection. It does not repair projections corrupted before the fix or authenticate externally supplied snapshots. Money posting, schema migrations, listener recovery, topic durability and relay behavior are unchanged.

## Fresh verification evidence

| Check | Observed result |
| --- | --- |
| Original parser baseline | 37 cases passed |
| Red: expanded parser suite against unchanged production code | 79 cases, 18 expected assertion failures: invalid input reached JDBC instead of throwing `IllegalArgumentException`; no test errors/skips |
| Red: real listener regression against unchanged production code | All 3 cases failed: source offsets committed but quarantine count was 0 instead of 1 |
| Focused green | 91 unit cases and 16 real integration cases passed; zero failures/errors/skips |
| Focused PIT, `BalanceProjection` | 10/10 KILLED, 39/39 lines covered; no survivors/uncovered/timeouts/errors |
| Final `spotless:apply clean verify -Pmutation` | 171 unit + 277 integration cases, zero failures/errors/skips; formatting/package pass; 113 seconds |
| Final expanded PIT gate | 92/94 KILLED, 193/201 lines; two unchanged factory methods uncovered; no survivors/timeouts/errors |
| SQL mutations within integration total | 12/12 passed |

The 79 [`BalanceProjectionTest`](../src/test/java/com/example/walletledger/messaging/BalanceProjectionTest.java) cases include invalid token/range matrices for every numeric field. Rejection cases assert no JDBC interaction. Raw JSON literals preserve decimal/exponent syntax. Positive cases assert exact SQL parameter values for sequence 1 / balance 0, values above `Integer.MAX_VALUE`, and `Long.MAX_VALUE`.

The three new [`BalanceListenerIT`](../src/test/java/com/example/walletledger/messaging/kafka/BalanceListenerIT.java) cases corrupt one field in a real outbox snapshot: schema version `4294967297`, sequence `27670116110564327423`, or balance `18446744073709551616`. They use the production listener and real Kafka/PostgreSQL to verify one failed delivery, durable quarantine with original key/payload bytes, committed source offset, unchanged prior projection and no poisoned consumed-event row. The original valid snapshot then arrives on the same partition with the same event ID and advances the projection. Authoritative wallet balance/sequence, ledger entries and outbox payloads remain unchanged. The focused integration total also includes existing deduplication/staleness, rollback, broker outage, injected database failures, retry, quarantine-commit and replay cases.

The permanent PIT target list now includes `BalanceProjection`; thresholds remain 80%, with no new exclusions. Its 10 mutations were also all killed in the full gate. The two uncovered mutations are unchanged `MessagingConfiguration.balanceTopic` and `relay` return values, already recorded in F-02. PIT mutates Java conditions and return values; it does not establish PostgreSQL transaction behavior. The real integration regressions provide that evidence.

Commands (repository root, `JAVA_HOME` set to Java 21):

```sh
./mvnw -B -ntp -Dtest=BalanceProjectionTest test
./mvnw -B -ntp test-compile failsafe:integration-test failsafe:verify \
  '-Dit.test=BalanceListenerIT#oversizedEventIntegersAreQuarantinedBeforeWritesAndFollowingSnapshotStillApplies'
./mvnw -B -ntp spotless:apply \
  -Dtest=BalanceProjectionTest,KafkaQuarantineTest,MessagingConfigurationTest test \
  failsafe:integration-test failsafe:verify -Dit.test=BalanceListenerIT,MessagingIT
./mvnw -B -ntp -Pmutation \
  -DtargetClasses=com.example.walletledger.messaging.kafka.BalanceProjection \
  org.pitest:pitest-maven:mutationCoverage
./mvnw -B -ntp spotless:apply clean verify -Pmutation
git diff --check
```

Surefire/Failsafe XML and PIT `mutations.xml` were inspected. Final source, tests, POM and formatting passed the complete pipeline; subsequent edits only update documentation, checked by source/link/diff review. The initial sandboxed unit run could not attach Mockito and is not red evidence. The baseline and subsequent runs used the approved environment with JVM attachment and Docker access. No Compose or live services were used. Logs `/tmp/f03-*.log` and reports under `target/` are ephemeral. Hosted CI and broader all-class coverage were not measured.
