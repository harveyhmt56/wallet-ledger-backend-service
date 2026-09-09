# Verification and pending work

Checked: 2026-09-09 against `682a1fd`; [baseline and retrieval rules](../../MEMORY.md).

## Evidence provenance

- This memory refresh inspected committed code, migrations, build/configuration and tests, and cross-checked README, build evidence and both reviews. No application test suite, benchmark, live HTTP probe or exploit was rerun during this refresh.
- Implementation commit: `f4e6b1a`. Both reviews name `4a1a954a844e951e74531303e855574551d6e14f`; that object is available locally. `git diff 4a1a954a844e951e74531303e855574551d6e14f 682a1fd` changes only the original `MEMORY.md` and the two review documents. Application/build/test trees match; the reviews are not evidence that remediation was implemented.
- [Build evidence](../build-evidence.md) historically records 15 unit/adapter tests, 32 integration tests, zero failures/errors/skips, and 19/19 killed PIT mutants. The [fact-checked review](../review-remediation-plan.md#verification-performed) reports reproducing those counts, packaging and Spotless. Treat them as attributed past runs, not a fresh passing gate.
- PIT covers four small domain classes (`MoneyRules`, `DailyRewardPolicy`, `ProjectionPolicy`, `BusinessException`); it does not prove service-wide or SQL mutation coverage. Historical red-green claims are not reconstructible from the available implementation commit.
- Review logs under `/private/tmp` and generated `target/` reports are ephemeral. Committed reports preserve observations, not the original machine-readable artifacts or a guarantee they remain available.
- Build evidence's “untracked memory preserved separately” describes its starting environment; the current Git history tracks the original memory in `f4e6b1a`. The [archive](archive/2026-09-07-plan.md) preserves that original text verbatim beneath a historical notice.

## CI addition — checked 2026-09-09

- Against baseline `14b1e8f` plus this CI change, [Maven verification](../../.github/workflows/verify.yml) now configures push/PR/manual runs on Ubuntu 24.04, Java 21, Docker/Testcontainers and `clean verify -Pmutation`, retaining available Surefire/Failsafe/PIT reports for 14 days even after failure. CI checks formatting instead of applying fixes.
- Fresh local verification passed: 15 unit/adapter tests, 32 real-container integration tests, zero failures/errors/skips, and 19/19 killed PIT mutants (no other statuses); packaging, Spotless and actionlint 1.7.12 passed. [CI evidence and sources](../build-evidence.md#github-actions-verification) record the environment and limits. GitHub-hosted execution/upload remain unverified; the production remediation items below remain pending apart from adding this CI configuration.

## Commands and test navigation

Run from the repository root with Java 21; integration/mutation gates require Docker and dependencies/images. See [README setup](../../README.md#how-to-run-setup-database-and-tests) and [pom.xml](../../pom.xml).

| Purpose | Command / evidence |
| --- | --- |
| Unit/adapter tests (`*Test`) | `./mvnw test` |
| Unit + integration (`*IT`) + formatting check + package | `./mvnw clean verify` |
| Full gate including PIT (80% mutation/coverage thresholds; fail on zero mutants) | `./mvnw clean verify -Pmutation` |
| Format Java / inspect formatting | `./mvnw spotless:apply` / `./mvnw spotless:check` |
| Optional local load measurement, excluded from default suites | `./mvnw -Dtest=LoadMeasurement test` |
| Generated evidence | `target/surefire-reports`, `target/failsafe-reports`, `target/pit-reports`, `target/load-report.json` |

| Behavior | Focused source to inspect |
| --- | --- |
| 100 debits of 10 from 500 → 50 successes, 50 rejections, zero; 100 same-key copies; credit/transfer/refund races; history | [WalletLedgerIT](../../src/test/java/com/example/walletledger/wallet/WalletLedgerIT.java) |
| Immutable/incomplete journals, outbox failure rollback, held wallet lock | [DatabaseSafeguardsIT](../../src/test/java/com/example/walletledger/wallet/DatabaseSafeguardsIT.java) |
| Stored replay/rejection and infrastructure rollback | [CommandExecutorIT](../../src/test/java/com/example/walletledger/idempotency/CommandExecutorIT.java) |
| UTC/streak, same completion with different keys, 500 players/100 slots, claim/capacity rollback | [RewardRacesIT](../../src/test/java/com/example/walletledger/rewards/RewardRacesIT.java), [RewardServiceIT](../../src/test/java/com/example/walletledger/rewards/RewardServiceIT.java) |
| HTTP validation/owner checks and two-instance replay | [HttpApiIT](../../src/test/java/com/example/walletledger/wallet/HttpApiIT.java), [TwoInstanceHttpIT](../../src/test/java/com/example/walletledger/wallet/TwoInstanceHttpIT.java) |
| Kafka outage/lease and manually invoked projection; Redis TTL | [MessagingIT](../../src/test/java/com/example/walletledger/messaging/MessagingIT.java), [RedisRateLimiterIT](../../src/test/java/com/example/walletledger/configuration/RedisRateLimiterIT.java) |

Tests use disposable PostgreSQL, not H2 or the Compose database. [Shared test configuration](../../src/test/java/com/example/walletledger/support/PostgresIntegrationTest.java) disables listener startup. [JwtSecurityTest](../../src/test/java/com/example/walletledger/configuration/JwtSecurityTest.java) uses a fake decoder and probe controller; real signature/issuer/audience checks and full endpoint authorization are not established by it.

## Pending remediation

All items below remain pending at the checked commit. The [fact-checked plan](../review-remediation-plan.md) owns details, evidence tags, priorities and acceptance criteria; consult it before the earlier [independent review](../review-by-harvey-with-claude.md), whose readiness verdict and some assumptions it corrects.

| Planned order | Remaining work and evidence limits |
| --- | --- |
| 1. Database validation | Replace quadratic historical-prefix validation through a new migration while preserving invariants; harden unqualified function relations/types/search path; add complete audit, populated-schema upgrade and deadline tests. Review reports ~24.3 seconds COMMIT at 20,000 entries and a TEMP-table bypass using arbitrary runtime-role SQL; no HTTP exploit was identified. |
| 2. Receipt privacy and authorization | Project caller-safe fresh **and stored** transfer receipts; add real controller authorization and configured JWT-decoder tests. Current `recipientBalanceAfter` leak is a static data-flow finding. |
| 3. Errors and audit fields | Separate known 409 conflicts, transient 503 failures and unexpected internal failures; preserve headers and useful field errors; add transfer/refund history relationships, rejection cases and accurate rate-limit retry guidance. |
| 4. Messaging | Verify production replication/minimum ISR; wire topic configuration; validate exact event types/ranges; durable poison quarantine/replay; listener/offset recovery and relay lease tests. Current single-copy acknowledgements are a durability risk, not a reproduced broker-loss test. |
| 5. Promotion contention | Early rejection for already-claimed/exhausted campaigns; retain locked authoritative checks and atomic credit/capacity for every possible winner. |
| 6. Production evidence | Review container/dependency patches while retaining required Java/Boot; deployment secrets and migration privileges; readiness, backup/restore, reconciliation/recovery drills, CI gates and updated documentation. |

After authorization for an implementation slice, follow its acceptance criteria with the smallest safe change; this queue does not itself authorize implementation. No pending product clarification remains from the original three decisions.

Historical local load figures live in [build evidence](../build-evidence.md#local-load-measurement); they exclude HTTP, Redis and Kafka relay, use short histories and establish no production capacity target. Compose is a local demonstration, not high availability. Broader repository refactoring and OpenAPI are deferred cleanup, not prerequisites for the demonstrated fixes.
