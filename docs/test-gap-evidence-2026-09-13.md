# Test gaps and coverage expansion — 2026-09-13

Baseline: `e66f363` on `qa/determinstic-guardrail`, initially clean. Work branch: `codex/close-test-gaps`. Java 21.0.8, Spring Boot 3.5.16, Maven 3.9.11, PIT 1.30.0/JUnit plugin 1.2.3, JaCoCo 0.8.13. Application code and migrations are unchanged.

The changes address the fact-checked test gaps in F-04–F-08, then add focused unit coverage. They preserve the current contracts and add three newly tested classes to the normal PIT gate. Both 80% thresholds and failure on zero mutants remain unchanged.

## Behavior now protected

| Area | New evidence |
| --- | --- |
| Refunds and wallet rejections | [RefundConcurrencyIT](../src/test/java/com/example/walletledger/wallet/RefundConcurrencyIT.java) forces two overlapping refunds using PostgreSQL locks, for credit and debit originals. Exactly one reversal succeeds; the loser must report `ALREADY_REFUNDED`. Assertions check inverse entries, final balance/sequence, one refund and outbox counts. [WalletRejectionsIT](../src/test/java/com/example/walletledger/wallet/WalletRejectionsIT.java) adds missing/suspended players, duplicate transfer/refund references, unsupported refunds, identifiers, metadata null/blank/length boundaries and pagination. [ProvisionConcurrencyIT](../src/test/java/com/example/walletledger/wallet/ProvisionConcurrencyIT.java) forces overlapping creation of the same player: one succeeds and one returns `PLAYER_EXISTS`, with exactly one player/account/wallet. Existing races now assert exact codes. |
| Rewards | [RewardRejectionsIT](../src/test/java/com/example/walletledger/rewards/RewardRejectionsIT.java) covers missing/suspended players across all four entry points, completion ownership/reference errors, unknown/disabled definitions, duplicate/exhausted promotions and metadata limits. Rejections leave money, evidence, entitlements, capacity and outbox rows unchanged. [RewardRacesIT](../src/test/java/com/example/walletledger/rewards/RewardRacesIT.java) checks exact loser codes. |
| Command execution | [CommandExecutorIT](../src/test/java/com/example/walletledger/idempotency/CommandExecutorIT.java) verifies rollback of each retry, exactly three attempts, final persistence/replay, recovery after exhaustion, interrupt preservation, nested-object canonicalization with significant array order, and 200-character caller/key acceptance. Retry failures are deliberately injected inside real PostgreSQL transactions; this is not a real deadlock/outage test. [CommandExecutorTest](../src/test/java/com/example/walletledger/idempotency/CommandExecutorTest.java) rejects malformed identities before any action or persistence interaction. |
| Projection and listener | [MessagingIT](../src/test/java/com/example/walletledger/messaging/MessagingIT.java) checks the first delivery immediately, duplicate/stale/zero snapshots and rollback of deduplication when projection writes fail. [BalanceListenerIT](../src/test/java/com/example/walletledger/messaging/kafka/BalanceListenerIT.java) exercises the production conditional listener bean with real Kafka/PostgreSQL and checks committed broker offsets. Its scenario also passed twice in one JVM in a separate repeatability check. |
| Unit boundaries | [BalanceProjectionTest](../src/test/java/com/example/walletledger/messaging/BalanceProjectionTest.java) rejects malformed events before database interaction. [OutboxRelayTest](../src/test/java/com/example/walletledger/messaging/OutboxRelayTest.java) checks delivery/failure counters and interruption. [ApiProblemsTest](../src/test/java/com/example/walletledger/configuration/ApiProblemsTest.java) exercises advice through MockMvc. [RewardControllerTest](../src/test/java/com/example/walletledger/rewards/RewardControllerTest.java) preserves exact command status/body and JSON versus problem-JSON media types. [RateLimitFilterTest](../src/test/java/com/example/walletledger/configuration/RateLimitFilterTest.java) checks 429, caller identity, bypasses and blocked dispatch; the Redis null-result path is covered. [JwtSecurityTest](../src/test/java/com/example/walletledger/configuration/JwtSecurityTest.java) adds filter-level role denials/admin access using its existing fake decoder. |

Service-level money, SQL and transaction guarantees are checked with PostgreSQL; mocks cover the smaller parser, filter, error-handler and relay coordination boundaries. Existing signed-JWT HTTP tests remain in the normal integration suite.

## Measurements

| Measure | Before | After |
| --- | ---: | ---: |
| Unit/adapter cases (`*Test`) | 27 | **97** |
| Integration cases (`*IT`) | 186 | **264** |
| Unit-only line coverage | 154/900 = 17.1% | **270/900 = 30.0%** |
| Unit-only branch coverage | 22/164 = 13.4% | **66/164 = 40.2%** |
| Combined line coverage | 816/900 = 90.7% | **875/900 = 97.2%** |
| Combined branch coverage | 112/164 = 68.3% | **164/164 = 100%** |
| Normal configured PIT gate | 26/26 killed | **52/52 killed** |
| Broader PIT (historical before / fresh after) | 233/307 = 76.0% killed | **290/307 = 94.5% killed** |

The unit-only before/after measurements are fresh, separate runs over unchanged production sources. The combined before figures are retained historical evidence from `760f4b0`; production/test inputs at the new baseline were unchanged. The combined after run is fresh. All 148 added cases are included in the final gate; reaching all instrumented branches does not cover every input value or operational scenario. Denominators include all application classes reported by JaCoCo, using its default generated-code filtering and no new exclusions. Coverage demonstrates execution, not correctness of every failure case.

Fresh full gate: **361 cases, zero failures/errors/skips**, formatting and packaging passed, approximately 64 seconds. PIT produced 52 killed mutants and no survived, uncovered, timed-out or errored outcomes. The added normal-gate targets are `ApiProblems`, `RateLimitFilter` and `RedisRateLimiter`; this remains a deliberately bounded gate.

## Mutation evidence

For this test-only change, red evidence comes from deliberately corrupted scratch implementations, followed by restored passing controls. No production defect was implemented merely to create a failing test.

| Deliberate mutation | New test detects |
| --- | --- |
| Remove player-provision advisory lock | Forced creation race leaks a duplicate-key exception instead of `PLAYER_EXISTS`. |
| Reject metadata at its exact length limit | Eight transfer/refund metadata cases fail instead of accepting the stated limits. |
| Change reward error media-type boundary / negate it | Exact 400 / all three controller response cases report the wrong content type. |
| Remove refund advisory lock | Both forced races expose a duplicate-key exception instead of the required loser receipt. |
| Remove transfer or refund reference guard | Duplicate reference no longer returns `BUSINESS_REFERENCE_USED`. |
| Disable completion or promotion duplicate guard | Rejection changes to the generic reference conflict instead of the precise claim conflict. |
| Remove daily player precheck | Foreign-key error replaces `PLAYER_NOT_FOUND`. |
| Remove completion-source validation | Invalid metadata is accepted or reaches SQL instead of a business rejection. |
| Negate projection deduplication | First delivery fails to create its projection. |
| Reject zero balance / allow zero sequence | Valid zero snapshot is rejected / invalid sequence is accepted. |
| Remove projection transaction | Failed projection update leaves its deduplication marker committed. |

These **15 single-change application-contract mutation experiments** caused the expected behavior failures. Two messaging cases are reported by JUnit as errors because the mutation causes an unexpected exception/missing row; their restored controls pass. Those are reproduced mutant effects, not setup failures. Separately, the first expanded unit PIT run found one survivor in the 404 detail selection; an exact detail assertion killed it in the final 52/52 run.

Final broader PIT: **290 killed, nine survived, eight uncovered, zero timed out or errored** (307 total; **94.5% killed**), passing both 80% thresholds in approximately 194 seconds. Scratch configuration targets all `com.example.walletledger.*` classes with `*Test` and `*IT`, three workers and default mutators. It excludes `ConfiguredJwtHttpIT` because of the known repeated-JVM fixture incompatibility, plus the two optional measurement classes. Signed-JWT tests remain in the normal full gate. This is a broader comparison, not the configured CI gate.

| Remaining outcome | Interpretation / limit |
| --- | --- |
| Refund positive-delta boundary | `> 0` versus `>= 0` is equivalent for valid nonzero ledger deltas; inferred from the schema invariant. |
| Transfer recipient precheck / promotion player precheck | Later guards preserve tested single-invalid-input rejection; removing early checks may change diagnostics or precedence for combined-invalid cases. |
| Retry multiplication/division | Retry count, rollback and interruption are tested; the exact backoff duration is not. |
| Four JWT bean and one listener bean survivors | Three role-mapping deletions fail in fresh-JVM checks below. Null-return bean mutations remain unassessed outside PIT; do not interpret these as proof of absent functional tests or safe changes. |
| Eight uncovered mutants | Four outbox gauges, reconciliation row mapping, local-authentication response and automatic topic/relay bean creation. |

The earlier broad diagnostic had 283 killed, five timed out, 11 survived and eight uncovered. Its timeout results prompted investigation instead of being treated as assertion kills. Final focused WalletService PIT has 76 killed / 11 survived / one uncovered (88 total), no timeouts/errors, retaining 80/80 thresholds. Null balance/provision checks and the forced provision race now kill the three former wallet timeouts. Final focused OutboxRelay PIT has 10 killed / four uncovered (14 total), no timeouts/errors; interrupted and ordinary failed sends now kill both former relay timeouts immediately.

Three JWT-role configuration deletions that survived broader PIT each produce two assertion failures in a fresh Maven/JVM run of `JwtSecurityTest`; the restored five-case control passes. Thus those broader survivors do not demonstrate absent role-mapping assertions. Context reuse/instrumentation is a suspected limitation, not a proven cause. The raw broader outcomes remain reported as observed.

The 25 uncovered combined lines are mainly outbox gauges, defensive serialization/reconciliation paths, local authentication and conditional bean/bootstrap wiring. These remain partial or unverified; the real listener test manually provisions its topic and does not verify automatic topic creation.

The reward rejection snapshot helper now compares complete rows for the affected entities and both ledger sides instead of serializing unrelated history. With 20,000 unrelated postings, a diagnostic ledger snapshot fell from a median 295.3 ms / 11.36 MB to 5.82 ms / 561 bytes. All 36 rejection cases then passed after that history in the same database; four previously checked reward mutants still failed after the helper change. This is local harness evidence, not a production performance result.

Persistent [machine-readable counts and outcome details](evidence/test-gaps-2026-09-13/summary.json) and [input SHA-256 manifest](evidence/test-gaps-2026-09-13/inputs.sha256.json) identify the verified files. Raw logs and XML remain under `/private/tmp/wallet-test-gaps-20260913` and focused directories `/private/tmp/wallet-test-gaps-wallet`, `/private/tmp/wallet-test-gaps-rewards` and `/private/tmp/wallet-test-gaps-messaging`; these are ephemeral. All 74 copied input files were hash-checked against the final working tree. The final resource check found no running Testcontainers; the existing Compose stack was not modified.

## Reproduction and limits

Use Java 21 and the repository wrapper. Disposable Testcontainers are required for integration tests; no paid/external service or existing Compose stack is used.

```sh
# Unit-only measurement; record this report before cleaning for the full run.
./mvnw -B -ntp clean org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent \
  test org.jacoco:jacoco-maven-plugin:0.8.13:report

# Normal complete gate, with optional combined coverage attached.
./mvnw -B -ntp clean org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent \
  verify -Pmutation org.jacoco:jacoco-maven-plugin:0.8.13:report
```

The broader run uses the documented scratch configuration with `./mvnw -B -ntp test-compile -Pmutation org.pitest:pitest-maven:mutationCoverage`; do not infer that the normal POM targets the full application.

The initial sandbox unit run could not attach Mockito; isolated rerunning with the required process permissions passed. Early agent command/formatting/Docker setup errors were corrected before counting controls or mutation evidence. Repository thresholds were not lowered. One preliminary outbox-only diagnostic used zero thresholds; the authoritative rerun restored 80/80 and correctly failed at 71% (10 killed, four uncovered gauges), with no timeouts/errors/survivors. This focused result is distinct from the passing configured and broader gates.

Known product issues remain: transaction-start outage classification, unbounded poison-event recovery, oversized event integers, missing 405 `Allow`, and absent JDBC socket timeout. This work does not add assertions that endorse those defects. Automatic topic creation, production scheduler/relay bean wiring, competing relay leases, live identity providers, hosted CI, multi-broker durability and backup/restore remain unverified. Full event validation and failure recovery need implementation plus their own regression tests.

Tool behavior and measurement scope were cross-checked against [PIT Maven configuration](https://pitest.org/quickstart/maven/), [JaCoCo Maven integration](https://www.jacoco.org/jacoco/trunk/doc/maven.html), [Spring 6.2.19 TransactionTemplate](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/transaction/support/TransactionTemplate.html) and [PostgreSQL 17 row locks](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS).
