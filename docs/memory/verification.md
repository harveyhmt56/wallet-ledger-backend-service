# Verification and pending work

Checked: 2026-09-10 against baseline `45b28ab` plus step 2 privacy/authorization; [baseline and retrieval rules](../../MEMORY.md).

## Step 2 privacy and authorization — fresh evidence, 2026-09-10

- [Canonical behavior, tests, red/green evidence and primary references](../api-security-step2.md): transfer success responses use a sender field allowlist after execution/replay; fresh and historical stored receipts cannot disclose recipient funds. Stored JSON, identities, fingerprints, posting code and migrations remain unchanged.
- Observed valid red: recipient funds escaped in the controller unit test and two real-PostgreSQL HTTP cases; seven invalid JWT configuration cases started unexpectedly while two controls passed. Test harness errors were corrected before counting red evidence.
- Final Java 21 `clean verify -Pmutation`: **27 unit/adapter + 186 integration cases**, zero failures/errors/skips; packaging and Spotless passed. PIT **26/26 killed**, no other statuses, including 4 transfer projection + 3 required JWT configuration mutants. Existing **12/12 SQL mutation cases** passed within the integration total.
- `HttpAuthorizationIT` adds 72 endpoint × caller cases and 9 input/entitlement cases with real controllers and PostgreSQL, using supplied test identities. `ConfiguredJwtHttpIT` adds 12 actual HTTP cases with Boot's decoder and loopback signed/JWK fixtures. Required issuer/audiences fail closed outside `local`; no live identity provider was used.
- Route authentication/authorization denials reserve no key or business writes. `COMPLETION_NOT_OWNED` is a stored business rejection with no money/claim changes, preserving established idempotency semantics. Full log `/private/tmp/step2-full-verify.log` and generated `target/` reports are ephemeral. Production provider integration, hosted CI and deployment remain unverified.

## Earlier review refresh — documentation only

- At `2fe9ce4`, the only change since `9ef2639` is Claude's second review. Source/tests, Git diffs, retained benchmark JSON and existing generated XML were inspected; no application test, benchmark, HTTP/SQL probe, hosted CI lookup or deployment was run in this refresh.
- Existing reports corroborate 15 unit/adapter + 90 integration cases (including 12 SQL mutation cases), zero failures/errors/skips and 19 KILLED PIT results. Generated artifacts are mutable; inspection is not a fresh gate or proof of exact commit provenance. Historical BUILD/LIVE/BENCH/DB claims stay attributed.
- [Second-pass fact check](../review-remediation-plan.md#second-pass-fact-check--2026-09-10) corrects the readiness verdict, incomplete HTTP coverage wording, blanket integrity-to-409 proposal, two-retry count, player-before-promotion-lock ordering, inherited TEMP privileges and audit-versus-commit semantics. The 35 emitted codes / 11 named assertions / 24 absent codes were confirmed statically.
- A controlled V4 deployment remains a release gate: startup Flyway uses its own datasource; serving Hikari timeouts do not apply. Follow the [upgrade runbook](../ledger-integrity-v4.md#populated-upgrades-and-operational-audit). Operation-semantic enforcement and supplemental plan checks are candidate future hardening, not implemented fixes.

## V4 remediation — evidence recorded 2026-09-09

- Step 1 is implemented in [V4](../../src/main/resources/db/migration/V4__indexed_ledger_integrity.sql); applied V1–V3 are unchanged. Per-entry predecessor and final-wallet/tail checks replace quadratic history scans; all integrity functions use qualified permanent objects and trusted invoker search paths.
- Observed real-PostgreSQL red before production edits: four TEMP-shadow bypasses committed, corrupt V3 fixtures upgraded silently, and a 20k-history credit took 25.692 seconds against a 10-second CI bound. Fresh V4 regressions cover preserved invariants, populated/fresh upgrades, rejected corruption, preflight waiting for writers, audit shadowing, actual `psql` success/failure exits and 16 concurrent long-history credit/debit/transfer/refund requests.
- Final `clean verify -Pmutation`: **15 unit/adapter + 90 integration cases**, no failures/errors/skips; packaging and Spotless passed. PIT: **19/19 killed**, no other statuses. Additional real-database mutation harness: **12/12 targeted SQL mutants killed**, with green controls and rejected invalid/setup outcomes. PIT still covers only the four domain classes; SQL mutants are targeted, not exhaustive audit/service coverage.
- Warmed local V3 credit samples at 1k/5k/10k/20k entries: 74.926/1519.784/6003.796/24930.376 ms. V4 credit medians: 1.667/1.573/1.374/1.685 ms (three samples per size); debit/transfer/refund also measured. Predecessor and tail plans each return one row through the existing unique index. These service-through-commit measurements are not a production SLO.
- JDBC fault injection verifies statement timeout does not bound deferred commit, PostgreSQL 17 transaction timeout terminates it with rollback and same-key retry, and a lost post-commit acknowledgement recovers the stored receipt without a second posting. Global transaction-timeout configuration remains unchanged.
- [Canonical V4 evidence and raw benchmark reports](../ledger-integrity-v4.md) contain commands, limits and source cross-checks. [Bounded maintenance audit](../../scripts/audit-ledger.sql) raises on mismatches; deployment scheduling and alert routing are documented, not externally installed. Full local log `/private/tmp/ledger-v4-final-verify.log` and generated `target/` reports are ephemeral. No deployed database or GitHub-hosted run was exercised.

## Earlier evidence provenance

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
| Optional warmed long-history benchmark | `./mvnw -Dtest=LedgerHistoryMeasurement test`; [V3 comparison command](../ledger-integrity-v4.md#long-history-measurements) |
| Generated evidence | `target/surefire-reports`, `target/failsafe-reports`, `target/pit-reports`, `target/load-report.json` |

| Behavior | Focused source to inspect |
| --- | --- |
| 100 debits of 10 from 500 → 50 successes, 50 rejections, zero; 100 same-key copies; credit/transfer/refund races; history | [WalletLedgerIT](../../src/test/java/com/example/walletledger/wallet/WalletLedgerIT.java) |
| Immutable/incomplete journals, outbox failure rollback, held wallet lock | [DatabaseSafeguardsIT](../../src/test/java/com/example/walletledger/wallet/DatabaseSafeguardsIT.java) |
| V4 integrity/TEMP regressions, populated preflight/full audit, deadlines, long history, SQL mutations | [V4 test navigation and evidence](../ledger-integrity-v4.md#observed-red--green) |
| Stored replay/rejection and infrastructure rollback | [CommandExecutorIT](../../src/test/java/com/example/walletledger/idempotency/CommandExecutorIT.java) |
| UTC/streak, same completion with different keys, 500 players/100 slots, claim/capacity rollback | [RewardRacesIT](../../src/test/java/com/example/walletledger/rewards/RewardRacesIT.java), [RewardServiceIT](../../src/test/java/com/example/walletledger/rewards/RewardServiceIT.java) |
| HTTP validation/owner checks and two-instance replay | [HttpApiIT](../../src/test/java/com/example/walletledger/wallet/HttpApiIT.java), [TwoInstanceHttpIT](../../src/test/java/com/example/walletledger/wallet/TwoInstanceHttpIT.java) |
| Kafka outage/lease and manually invoked projection; Redis TTL | [MessagingIT](../../src/test/java/com/example/walletledger/messaging/MessagingIT.java), [RedisRateLimiterIT](../../src/test/java/com/example/walletledger/configuration/RedisRateLimiterIT.java) |

Tests use disposable PostgreSQL, not H2 or the Compose database. [Shared test configuration](../../src/test/java/com/example/walletledger/support/PostgresIntegrationTest.java) disables listener startup. [JwtSecurityTest](../../src/test/java/com/example/walletledger/configuration/JwtSecurityTest.java) still uses a fake decoder and probe controller. The new [signed-token HTTP tests](../../src/test/java/com/example/walletledger/configuration/ConfiguredJwtHttpIT.java) and [controller role matrix](../../src/test/java/com/example/walletledger/wallet/HttpAuthorizationIT.java) provide separate step 2 evidence above.

## Pending remediation

Steps 1–2 are implemented and verified locally above; later items remain pending. The [fact-checked plan](../review-remediation-plan.md) owns original findings and acceptance criteria; [V4 evidence](../ledger-integrity-v4.md) supersedes its step-1 implementation status. The [independent review](../review-by-harvey-with-claude.md) now includes the 2026-09-10 fact-check corrections; the plan owns remaining acceptance criteria.

| Planned order | Remaining work and evidence limits |
| --- | --- |
| 1. Database validation | **Implemented/verified locally:** V4, preserved invariants, full/preflight audit, TEMP hardening, deadline tests and long-history benchmarks. Controlled migration before rollout, audit scheduling/alerts and production latency targets remain operational adoption work. Generic triggers do not enforce all operation semantics; inverse refunds are audit-checked, not commit-enforced. |
| 2. Receipt privacy and authorization | **Implemented/verified locally:** sender response projection covers fresh/stored replay; 81 controller authorization/input cases, 12 real signed-JWT HTTP cases and required issuer/audience startup validation. Production identity-provider integration remains deployment work. [Evidence](../api-security-step2.md). |
| 3. Errors and audit fields | Separate known 409 conflicts, transient 503 failures and unexpected internal failures; preserve headers and useful field errors; add transfer/refund history relationships, rejection cases and accurate rate-limit retry guidance. |
| 4. Messaging | Verify production replication/minimum ISR; wire topic configuration; validate exact event types/ranges; durable poison quarantine/replay; listener/offset recovery and relay lease tests. Current single-copy acknowledgements are a durability risk, not a reproduced broker-loss test. |
| 5. Promotion contention | Early rejection for already-claimed/exhausted campaigns; retain locked authoritative checks and atomic credit/capacity for every possible winner. |
| 6. Production evidence | Review container/dependency patches while retaining required Java/Boot; deployment secrets and migration privileges; readiness, backup/restore, reconciliation/recovery drills, CI gates and updated documentation. |

After authorization for an implementation slice, follow its acceptance criteria with the smallest safe change; this queue does not itself authorize implementation. No pending product clarification remains from the original three decisions.

Historical local load figures live in [build evidence](../build-evidence.md#local-load-measurement); they exclude HTTP, Redis and Kafka relay, use short histories and establish no production capacity target. Compose is a local demonstration, not high availability. Broader repository refactoring and OpenAPI are deferred cleanup, not prerequisites for the demonstrated fixes.
