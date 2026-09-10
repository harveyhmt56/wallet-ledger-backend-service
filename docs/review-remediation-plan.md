# Wallet ledger review: fact check and proposed changes

Initial review: 2026-09-09; second-pass fact check: 2026-09-10 at `2fe9ce4`. Later step 2 remediation against `45b28ab` is recorded in [API security evidence](api-security-step2.md); the second-pass section below retains its historical scope.

Original reviewed commit: `4a1a954a844e951e74531303e855574551d6e14f`

Original branch: `codex/implement-wallet-ledger`

Reference report: [review-by-harvey-with-claude.md](/Users/harvey/Projects/wallet_ledger_backend_service/docs/review-by-harvey-with-claude.md)

## Second-pass fact check — 2026-09-10

**V4 resolves R1/R2; production release gates remain.** `2fe9ce4` changes only Claude's review after implementation commit `9ef2639`. The current review's original “MEETS_ALL_REQUIREMENTS” and “0 serious” summary overlooked the still-open transfer disclosure (R3), Kafka durability/recovery (R4/R5), and authorization/error gaps (R6). No new ordinary HTTP money-corruption path was established by this check.

This refresh changed documentation only. It inspected current source/tests, Git diffs, committed benchmark JSON and existing generated reports, and cross-checked Spring, PostgreSQL, Kafka and RFC documentation. It ran no Maven gate, benchmark, SQL/HTTP probe, deployment or GitHub CI lookup. Existing local XML totals are 15 unit/adapter tests and 90 integration cases, zero failures/errors/skips; the 90 include 12 SQL mutation cases. PIT XML contains 19 KILLED results. Reports corroborate past counts, not a fresh run, immutable commit attribution or flake-free behavior.

| Second-pass claim | Fact check and disposition |
| --- | --- |
| F1: V4 repairs quadratic validation and TEMP shadowing | Confirmed in source and retained evidence. Applied V1–V3 are unchanged. Indexed lookup is not literal constant-time I/O. [Canonical V4 evidence](ledger-integrity-v4.md) supersedes the historical R1/R2 status below. |
| F1: audit means a future preflight will pass | Too strong: no findings describe only the audited snapshot and defined checks. Migration must validate again under its own locks. Audit scheduling/alerts are documented, not installed. |
| F2: no HTTP authorization tests | Incorrect absolute wording. `HttpApiIT` covers anonymous/foreign-owner/forbidden-credit requests; seven other route families lack HTTP coverage. `JwtSecurityTest` uses a fake decoder and probe. Step 2 remains necessary. |
| F3: 24 of 35 codes lack named assertions | Confirmed by enumerating emitted Java code strings and inspecting assertion contexts: 11 named, 24 absent. This is not a branch-coverage metric. |
| F4: all integrity failures should be 409; unreachable today | Neither follows from pre-checks. Recognized business conflicts need 409, transient outages 503, unexpected invariant/programming failures 500; test statement and deferred-commit wrappers. |
| F5: campaign lock precedes any eligibility check | `requirePlayer` checks player existence/status first. Campaign duplicate/exhaustion checks follow the lock; contention risk remains, with no new load measurement. |
| F6–F8 and input clarity | History omissions, generic validation and envelope differences are confirmed in source. `instance` is optional. Historical TDD/LIVE observations remain attributed, not newly reproduced. |
| N1: V4 startup/rolling-deployment risk | Valid operational gate. Only pending migrations execute. Flyway uses its own datasource, outside Hikari's init timeouts; commands allow three attempts total, not three retries. Actual outage duration and external database limits were not measured. See the [upgrade runbook](ledger-integrity-v4.md#populated-upgrades-and-operational-audit). |
| N2: replace timing bound with query-plan assertion | Keep the service-through-commit bound. Supplemental representative plan/index checks can help, but a standalone query's plan cannot prove trigger execution behavior. |
| N3: operation semantics absent from SQL guarantees | Confirmed static boundary: generic journal checks do not enforce operation/account/sign combinations. `refund_inverse` is audit-only, not a commit trigger. Service logic builds the intended entries; adding audit detection alone would not prevent malformed direct SQL. |
| N4: one-line TEMP removal costs nothing | Unsupported. `PUBLIC` grants TEMP by default; revoking only from the app role leaves inherited access. Review effective grants and legitimate users first. Current V4 protection deliberately works with TEMP retained. |
| Suspended player guard is vestigial | Incorrect: no management API/runtime UPDATE exists, but runtime INSERT can specify SUSPENDED and the migration owner can update status. |

Source anchors: [HTTP tests](../src/test/java/com/example/walletledger/wallet/HttpApiIT.java), [command retry/replay](../src/main/java/com/example/walletledger/idempotency/CommandExecutor.java), [reward ordering](../src/main/java/com/example/walletledger/rewards/application/RewardService.java), [V1 grants/constraints](../src/main/resources/db/migration/V1__ledger.sql), [V4 audit/triggers](../src/main/resources/db/migration/V4__indexed_ledger_integrity.sql). Framework semantics were cross-checked with [Boot 3.5 initialization](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html), [Spring integrity exceptions](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/dao/DataIntegrityViolationException.html), [PostgreSQL REVOKE](https://www.postgresql.org/docs/17/sql-revoke.html), [Kafka durability settings](https://kafka.apache.org/39/configuration/topic-level-configs/) and [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html).

Retain steps 2–6, with CI configuration already added at `79032c9`. Execute the controlled V4 upgrade before any production rollout; this is operational adoption of step 1, not a reason to edit an applied migration. Operation-semantic enforcement and plan assertions are candidate later database hardening, requiring a new migration where applicable and real PostgreSQL evidence. This review does not authorize implementation.

## Decision and scope

**Changes are necessary before a production release.** Core features are implemented and prior automated checks passed, but that does not establish production readiness. The original wallet-history and TEMP-shadow blockers were resolved by V4. Step 2 closes transfer disclosure and adds authorization/JWT evidence; errors, messaging and production adoption remain open.

Retain the architecture: one Spring Boot service, PostgreSQL transactions and ordered wallet locks, immutable double-entry journals, persistent idempotency, trusted reward evidence, Redis rate limiting and a Kafka outbox. A rewrite or microservice split is unnecessary.

The original 2026-09-09 review changed documentation only and preserved the then-current memory/external review. Later commits added CI and V4. This 2026-09-10 refresh updates the review and related docs/memory without changing Java, SQL, tests, build or runtime configuration. Review documents are evidence summaries, not instructions to execute a backlog.

Confirmed product scope remains:

- One whole-unit in-game currency.
- Trusted servers record action completion; clients request claims and cannot choose reward amounts.
- Full reversal of credits and debits only; an unaffordable reversal fails atomically.
- Original journal history is immutable; a transaction can be fully reversed only once.
- Partial refunds and transfer reversals are outside the initial scope.

## Verification performed

The following evidence is from the original 2026-09-09 review of `4a1a954`, not the current documentation refresh. Current V4 evidence is linked above.

The following command was reproduced with Java 21.0.8 and disposable Docker test containers:

```sh
JAVA_HOME="$(/usr/libexec/java_home -v21)" ./mvnw -B -ntp clean verify -Pmutation
```

| Check | Independently observed result |
| --- | --- |
| Unit/adapter tests | 15; zero failures, errors or skips |
| Integration tests | 32; zero failures, errors or skips |
| PIT | 19 evaluated mutants, all killed; zero uncovered or errored mutants |
| Mutation scope | MoneyRules, DailyRewardPolicy, ProjectionPolicy, BusinessException |
| Packaging and Spotless | Passed |
| Full build time | 26.896 seconds on this local run |

The mutation percentage applies to those four small classes, not the entire service or SQL. One passing run does not establish that a concurrency test is never flaky.

Build outputs under `target/` were regenerated. The build log is [wallet-ledger-review-20260909-verify.log](/private/tmp/wallet-ledger-review-20260909-verify.log). PostgreSQL benchmarks and adversarial checks used a separate synthetic database; its disposable container was removed. This review did not mutate the existing Compose/demo database or reproduce the other reviewer's historical live HTTP probes. Timing results below are local single-post measurements, not production capacity guarantees.

## Requirement assessment

The table below describes the original V3 assessment. V4 has since resolved the long-history and TEMP-shadow gaps and added full audit/deadline tests; other gaps remain.

| Requirement | Original V3 assessment | Evidence or remaining production work |
| --- | --- | --- |
| Required technology | Implemented | Java 21 / Boot 3.5.16, PostgreSQL, Redis, Kafka, Flyway and Compose are present |
| Credit/debit/insufficient funds | Implemented, tested | Shared posting boundary, checked arithmetic, real PostgreSQL debit race; long-history cost blocks sustainable operation |
| Current balance/history | Implemented | PostgreSQL balance reads and sequence cursors; improve transfer/refund audit fields and prevent recipient-balance disclosure |
| Permanent explanatory records | Implemented at application level | Immutable journals and references; harden SQL checks and validate backup/restore before deployment |
| Idempotency/concurrent writes/atomic failures | Strong baseline | Persistent reservation/result, savepoint rollback, ordered locks, transfer/refund races and fault injection; preserve these through changes |
| Clear invalid-input handling | Partial | Inputs are rejected safely, but generic errors omit useful field information |
| Daily login/trusted rewards/promotions | Implemented, core races tested | Preserve claim uniqueness and atomic capacity; add HTTP, rejection and contention coverage |
| Full refund policy | Implemented | Inverse entries, original reference and no overdraft; add exact rejection and entitlement-retention tests |
| Domain events | Implemented with production gaps | Outbox works; topic durability, poison recovery, listener tests and strict event validation need work |
| Test quality | Good baseline with material gaps | Actual HTTP authorization boundary, long histories, temporary-schema bypass and real listener recovery are insufficiently covered |
| Documentation | Required sections present | Correct reconciliation and coverage claims; add measured limits and production deployment evidence |

No additional ordinary HTTP path causing an overdraft, duplicate debit or partial transfer was established. Findings requiring direct SQL or external Kafka input are labeled accordingly.

## Fact check of the reference report

Historical first-pass claims and findings follow; use the second-pass table above for current status.

| Report claim | Finding | Correction or response |
| --- | --- | --- |
| F1: quadratic commit-time wallet validation | **Reproduced; P1** | Fix before production. Preserve all invariants when replacing the trigger; indexed lookups are logarithmic, not literally O(1). |
| F1: 15-second statement timeout does not bound this commit work | **Reproduced and source-supported** | Verify an actual transaction/commit deadline with the JDBC path; increasing statement timeout is not a fix. |
| F1: keep existing periodic full reconciliation | **Incorrect assumption** | The current endpoint compares aggregate balances only, and is not scheduled. Add the full audit needed by incremental validation. |
| F2: missing HTTP authorization coverage | **Confirmed** | The roles look correct statically; lack of tests is not proof of a role bypass. Exercise the real controllers and JWT validation boundary. |
| F3: error-code coverage gaps | **Confirmed in substance** | Prioritize money/entitlement outcomes and stable error assertions; a count of asserted code strings is not sufficient coverage evidence. |
| F4: all database exceptions become retryable 503 | **Confirmed; proposed fix needs correction** | Map known business conflicts to 409, retryable failures to 503, and unexpected constraints/programming errors to internal 500 with observability. Never map every integrity violation to 409. |
| F5: promotion row contention for inevitable rejections | **Confirmed** | An unlocked early rejection check can help, but every possible winner must still pass the locked check. Existing quota correctness is sound. |
| F6: history lacks refund origin/counterparty | **Confirmed** | Add caller-safe audit fields; do not expose another player's balance. |
| F7: inconsistent problem envelopes | **Confirmed, mostly API quality** | `instance` is optional. Standardize titles, safe field errors and headers without breaking deterministic business replay unnecessarily. |
| F8: TDD process cannot be corroborated from squashed history | **Correct limitation; not a runtime defect** | Keep historical claims attributed. Do not invent missing commits or claim the current build proves the past red-green sequence. |
| Claimed build/test/PIT totals | **Reproduced** | Do not extend these results into a claim of complete HTTP/JWT/Kafka listener or production-load coverage. |

Spring categorizes integrity failures as nontransient; that does not determine whether a particular violation is a legitimate client conflict or an application invariant failure. [Spring exception hierarchy](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/dao/DataIntegrityViolationException.html). Problem Details standardizes representation but does not require an `instance` member. [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html).

## Findings that determine the change plan

### R1 — P1: posting cost grows quadratically with wallet history

Evidence: [V1__ledger.sql:140](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/resources/db/migration/V1__ledger.sql:140), [V1__ledger.sql:146](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/resources/db/migration/V1__ledger.sql:146).

For every historical player entry, the trigger sums its historical prefix. It runs for both the wallet update and the new player entry. Wallet locks and database connections remain occupied while this deferred work executes.

| Existing player entries | Independently measured COMMIT |
| ---: | ---: |
| 1,000 | 81.808 ms |
| 5,000 | 1,547.874 ms |
| 10,000 | 6,041.190 ms |
| 20,000 | 24,289.620 ms |

Doubling history from 10,000 to 20,000 increased commit time approximately 4.02 times. The final case completed despite `statement_timeout = '15s'`.

Fixtures were bulk-loaded by a privileged connection into an isolated PostgreSQL 17.6 container with triggers temporarily disabled for setup only. Triggers were restored before the measured posting as `wallet_app`. These measurements corroborate the report's mechanism and scale, not a precise production SLO.

The PostgreSQL 17.6 backend disables the statement timeout in `finish_xact_command()` before `CommitTransactionCommand()`. [Official PostgreSQL 17.6 source](https://raw.githubusercontent.com/postgres/postgres/REL_17_6/src/backend/tcop/postgres.c). The existing Spring transaction timeout must not be assumed to interrupt JDBC commit/deferred-trigger execution without a test.

### R2 — P2: temporary-table shadowing bypasses a database integrity guard

Evidence: unqualified relation references in [V1__ledger.sql:103](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/resources/db/migration/V1__ledger.sql:103) and [V1__ledger.sql:135](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/resources/db/migration/V1__ledger.sql:135); role setup in [01-roles.sql:6](/Users/harvey/Projects/wallet_ledger_backend_service/docker/postgres/01-roles.sql:6).

**Reproduced using arbitrary SQL as `wallet_app`; no HTTP exploit was identified.** Temporary tables named `wallet` and `ledger_entry` were created in a fresh runtime-role connection. Their copied values were changed, followed by a balance-only update to `public.wallet`. The trigger validated the temporary copies and allowed the persistent update to commit: wallet balance **1101**, permanent ledger sum **1001**.

The default database TEMP privilege remains available despite revoking CREATE on `public`. Temporary schemas can precede permanent relations during name resolution. Schema-qualify integrity-function relation/type references and use a trusted function search path with `pg_temp` last. Remove unused TEMP privileges as additional protection, not the sole repair. Do not add unnecessary `SECURITY DEFINER` privileges. [PostgreSQL name resolution](https://www.postgresql.org/docs/17/runtime-config-client.html), [privileges](https://www.postgresql.org/docs/17/ddl-priv.html), [safe function search paths](https://www.postgresql.org/docs/17/sql-createfunction.html).

### R3 — P2: transfer receipts disclose another player's balance

Evidence: [WalletService.java:126](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/wallet/application/WalletService.java:126) adds `recipientBalanceAfter`, and the player transfer endpoint returns it. Direct reads of another player's balance are forbidden by [WalletController.java:105](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/wallet/api/WalletController.java:105).

**Conclusive static data flow, not a live write probe:** a sender can learn the recipient's complete balance by making a small transfer. Removing the field only from new receipts is insufficient: [CommandExecutor.java:87](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/idempotency/CommandExecutor.java:87) replays historical stored JSON.

Use a caller-safe transfer response projection for both fresh and stored results. Return the sender's balance and recipient identity, but not the recipient's funds. Do not rewrite immutable journals. Document the intentional removal of this sensitive response field. [OWASP property-level authorization](https://owasp.org/API-Security/editions/2023/en/0xa3-broken-object-property-level-authorization/).

### R4 — P1 production configuration gate: Kafka topic durability

Evidence: [MessagingConfiguration.java:39](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/messaging/kafka/MessagingConfiguration.java:39) creates the topic with replication factor 1 outside any local-only restriction. `acks=all` can therefore acknowledge a single copy. Broker storage loss can lose an acknowledged event whose outbox row is already marked delivered.

This is a static durability risk, not a reproduced broker-loss test. Keep single-broker settings for local use, but provision and verify durable production topics. A typical production policy is replication 3, minimum in-sync replicas 2 and producer `acks=all`; validate the actual existing topic, not only the desired configuration. [Kafka 3.9 topic configuration](https://kafka.apache.org/39/configuration/topic-level-configs/).

### R5 — P2: notification recovery and validation are incomplete

- [MessagingConfiguration.java:53](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/messaging/kafka/MessagingConfiguration.java:53) configures unlimited retry. Permanent malformed-event failures can prevent later records from progressing. Add durable quarantine and recovery for permanent contract errors, while retaining retries for temporary database failures. Spring documents effectively infinite retries for this setting. [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).
- [BalanceProjection.java:26](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/messaging/kafka/BalanceProjection.java:26) narrows JSON integers to `long` without first checking representable range; schema version also permits coercion. An oversized integral JSON number can narrow to a valid positive value. Require exact types and range checks before conversion. This is a static/binary-verified consumer issue; it does not change authoritative wallet balances. [Java BigInteger conversion](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigInteger.html#longValue()).
- [OutboxRelay.java:60](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/messaging/outbox/OutboxRelay.java:60) leases 100 events for 60 seconds but sends serially with potentially much longer cumulative waits. Another worker can reclaim the batch before the first finishes. Token fencing protects updates, but avoidable duplicate publication and inaccurate delivered metrics remain. Align ownership duration and work limits; retain the at-least-once contract.
- Current Kafka tests use a real broker but call the projection manually. [MessagingIT.java:176](/Users/harvey/Projects/wallet_ledger_backend_service/src/test/java/com/example/walletledger/messaging/MessagingIT.java:176) does not exercise the real Spring listener's recovery or offset handling; the shared test configuration disables listener startup.

### R6 — P2: error classification and the HTTP security boundary need stronger evidence

Evidence: [ApiProblems.java:30](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/configuration/ApiProblems.java:30), [HttpApiIT.java:98](/Users/harvey/Projects/wallet_ledger_backend_service/src/test/java/com/example/walletledger/wallet/HttpApiIT.java:98), [JwtSecurityTest.java:31](/Users/harvey/Projects/wallet_ledger_backend_service/src/test/java/com/example/walletledger/configuration/JwtSecurityTest.java:31).

The database handler currently converts even invariant and SQL programming failures into a retryable outage, without useful failure logging. The role tests omit several money/claim endpoints. The JWT test uses a fake decoder and a synthetic probe; it does not prove actual signature, issuer, expiry or audience checks.

Add tests of application authorization and its configuration, rather than attempting to re-test the entire Spring Security implementation. Use a local signing key/JWK fixture with the real configured decoder and the real controllers. Require production issuer/audience settings so the deployment cannot silently omit the intended audience restriction. [Spring Security JWT configuration](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).

## Ordered implementation plan

Status updated 2026-09-10 against baseline `45b28ab` plus step 2: step 1 is implemented at `9ef2639`; see [regression, upgrade, timeout and benchmark evidence](ledger-integrity-v4.md). Step 2 is implemented with [local privacy/authorization evidence](api-security-step2.md). Steps 3–5 and production adoption remain pending; step 6 already has CI configuration, but hosted execution and other deployment evidence remain unverified.

### Step 1: preserve behaviour and repair database safeguards

Affected areas: new Flyway migration(s), database safeguard tests, long-history benchmark, full-audit SQL/service and documentation. Keep applied V1–V3 unchanged; the original plan used V4, now implemented. Any further schema/function changes need a new migration.

1. Add failing regressions for R1 and R2 and for the safeguards that must survive optimization.
2. Add a consistent-snapshot preflight audit of existing data. Use window sums/LAG for running balances and sequences; verify ownership and complete journals. Refuse silent migration over inconsistent data. Do not repair history by rewriting ledger records.
3. Replace full-history posting checks with indexed predecessor and final-tail validation. For each new player entry, verify ownership, required metadata, sequence-1 zero base, predecessor existence and `previous balance + signed amount = balance_after`, using numeric-safe arithmetic.
4. Retain a wallet insert/update guard: the final current wallet balance and sequence must match its final ledger tail; an empty wallet must be zero/zero. Validate every new entry and support multiple valid postings to the same wallet in one transaction. Do not compare intermediate deferred `NEW` wallet images against the final tail.
5. Preserve balanced-journal, unique sequence and immutable-history protections. Qualify all integrity-function references and harden name resolution.
6. Keep a separate full audit for operational use. The existing [reconciliation method](/Users/harvey/Projects/wallet_ledger_backend_service/src/main/java/com/example/walletledger/wallet/application/WalletService.java:264) is only an aggregate balance comparison; extend it or add a bounded maintenance audit that also checks running balances, sequence continuity, ownership, journals and inverse refunds. Document how it is invoked/scheduled and alerts on mismatches.
7. Verify real commit/transaction timeout behaviour with PostgreSQL/JDBC fault injection. Consider PostgreSQL 17 `transaction_timeout` as a tested safeguard; do not substitute an increased timeout for the query fix. Retain same-key recovery for uncertain commit results.

Acceptance evidence:

- Direct wallet balance/sequence drift, missing wallet updates, wrong first/intermediate running balance, missing predecessors, account mismatch and incomplete journals are rejected.
- Multiple valid postings in one outer transaction commit correctly; an invalid intermediate posting fails even if the final aggregate balance appears correct.
- Runtime-role temporary shadowing cannot defeat validation; ordinary role restrictions and append-only history still hold.
- Migration from a populated V3 database succeeds; corrupted fixtures fail preflight clearly; a fresh database also starts correctly.
- Long-history credit, debit, transfer and refund, including concurrent requests, preserve invariants. Benchmark 1k/5k/10k/20k histories after warm-up; inspect indexed plans/rows and show the quadratic curve has gone. CI uses a documented generous completion bound; production latency targets require workload/hardware agreement.

### Step 2: close transfer disclosure and prove API authorization

**Implemented and verified locally, 2026-09-10.** See [behavior, regressions and evidence limits](api-security-step2.md). The criteria below remain the scope of the remediation.

Affected areas: transfer response DTO/projection, wallet controller response mapping, HTTP/JWT integration tests and production auth configuration validation.

- Filter recipient balance from fresh transfers and old stored replays; preserve journal/receipt identities and the sender's own financial result.
- Add real-controller endpoint × role tests: anonymous, roleless, owning/other PLAYER, SERVICE and ADMIN. Cover transfers, refunds, every claim, completion recording, reads and reconciliation.
- Assert authentication/route authorization denials produce no journal, wallet, claim or idempotency writes. Preserve the established stored rejection for business failures such as `COMPLETION_NOT_OWNED`, with no money/entitlement changes. Verify sender identity comes from authentication and client input cannot select a reward amount or forge trusted completion.
- Verify legitimate JWTs and rejection of wrong issuer/audience, expired tokens and invalid signatures using isolated local fixtures; do not contact a live identity provider.

Acceptance: a sender sees no recipient balance on initial response, ordinary replay or replay of a pre-change stored receipt; ownership checks remain enforced; every privileged route has explicit positive and negative HTTP evidence.

### Step 3: make rejections accurate, clear and auditable

Affected areas: API problem handling, shared error construction, named constraint translation, focused rejection tests and history response DTOs.

- Known business-uniqueness conflicts map to the documented 409/code. Transient or availability failures map to 503 with suitable retry guidance. Unexpected constraints, invariant failures and programming errors become internal 500 responses with correlation, safe server logs and alert metrics.
- Cover both statement-time and deferred commit-time failure wrappers. Assert rollback and same-key recovery; do not mark a failed/uncertain operation as a completed success.
- Include safe field/path violations for invalid requests. Preserve standard headers such as `Allow`; do not leak SQL, secrets or raw rejected sensitive values.
- Unify problem titles/media type/code conventions across MVC, security and command rejections. Keep stored business responses deterministic; request correlation can remain a response header. `instance` remains optional.
- Add missing-player, duplicate/unsupported refund, suspended-player, self-transfer, reference conflict, disabled/exhausted/duplicate promotion, wrong/foreign completion and pagination cases. Assert error code and unchanged money/entitlement state, not HTTP status alone.
- Add `originalTransactionId` to refund history and the appropriate counterparty identity to transfer history. Keep other players' funds private and preserve cursor behaviour.
- Return rate-limit retry guidance based on actual/configured window rather than hardcoded 60 seconds; validate positive limit/window configuration.

Acceptance: rejected money paths have explicit status/code and no partial writes; errors identify invalid fields; history explains cancellation and transfer relationships; existing idempotency contracts remain covered.

### Step 4: make event delivery suitable for production

Affected areas: messaging configuration, strict event DTO/parser, outbox relay, a quarantine migration/recoverer, listener integration tests and operations documentation.

- Make the topic name consistent across producer, listener and configuration. Keep automatic replication-1 topic creation local/test-only; provision and verify production topic replication and minimum ISR.
- Validate exact event field types, long ranges and schema version before deduplication/projection writes.
- Add durable quarantine for permanent contract errors. A PostgreSQL table keyed by consumer group/topic/partition/offset is a suitable small addition here; retain original payload and error metadata with restricted access. Advance the source offset only after quarantine commits. Provide an audited operational replay procedure and alerting.
- Retry temporary database failures without silently discarding their records. Preserve consumed-event deduplication with the projection update in one transaction.
- Align relay batch size, send deadlines and lease duration; renew or recheck ownership as needed before further sends. Keep token-fenced acknowledgement updates and count only successful delivery markings. Duplicate delivery remains permitted.

Acceptance evidence:

- Real Spring listener consumes malformed/unsupported/out-of-range input followed by a valid same-partition event; quarantine succeeds and valid work progresses.
- Quarantine failure or temporary database failure leaves the record recoverable.
- Restart after consumer database commit but before offset commit yields one logical effect.
- Two relay workers survive lease expiry, stale acknowledgement and retry without losing pending events or overwriting another worker's ownership.
- Multi-broker durability tests verify acknowledgement behaviour under broker loss/insufficient ISR; inspect existing topic settings. Wallet commands still commit while Kafka is unavailable, with notifications recoverable afterwards.

### Step 5: reduce promotion rejection contention

Affected areas: reward repository/service and targeted concurrency tests.

- Use read-only early checks for already-claimed/exhausted cases under the current no-restoration policy.
- Retain the campaign lock and all authoritative rechecks for every possible winner. Do not reserve slots from an unlocked result or move quota ownership to Redis.
- Preserve atomic credit, capacity increment and unique player claim.

Acceptance: exactly 100 distinct winners from 500 eligible players; repeated claims with different keys grant once; failure consumes no slot; inevitable rejections do not queue behind a held campaign row. Tune the stress harness so slow CI reports infrastructure saturation separately from an incorrect quota outcome.

### Step 6: production configuration, recovery evidence and documentation

- Preserve required Java 21 and Spring Boot 3.5.16. Update tested container patch levels and review dependency/image advisories. PostgreSQL 17.6 is behind the official 17.11 security release; do not claim every listed vulnerability is exploitable in this application. [PostgreSQL 17.11 release](https://www.postgresql.org/docs/17/release-17-11.html), [PostgreSQL 17 security information](https://www.postgresql.org/support/security/17/).
- Separate production from demo credentials and topic defaults. Enforce a separate migration/validation step with writers quiesced and measured migration-session limits before rolling out V4. Disable startup Flyway on serving instances only after that process exists; serving instances should not retain migration credentials. Local Compose can retain automatic migrations. See the [V4 upgrade runbook](ledger-integrity-v4.md#populated-upgrades-and-operational-audit).
- Choose and test readiness behaviour for unavailable PostgreSQL. Recommended here: database availability affects readiness; liveness remains independent, and optional Redis/Kafka outages are surfaced as degradation rather than disabling safe wallet writes. Spring does not add external health indicators to readiness automatically. [Spring Boot probe semantics](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html).
- Verify a database backup/restore and reconciliation drill, documented retention, pending-event recovery, operator alerts and bounded shutdown. Infrastructure-specific deployment remains a separately verified release prerequisite, not something proven by local Compose.
- CI configuration was added at `79032c9`; verify hosted execution and report retention. For implementation work, run focused tests during development, then one complete gate after the final change.
- Update README/build evidence with reproduced facts, added tests, measured long-history behaviour, error/API changes and remaining limits. Preserve the five required README sections. Attribute historical TDD claims rather than rewriting Git history to make them appear verified.

## Deferred cleanup

Typed caller-visible receipts/history and typed balance events directly support the fixes above and should be introduced in those slices. A broader WalletService/repository split, moving HTTP status out of the domain exception, unified SQL style and OpenAPI documentation are useful follow-up cleanup. They are not prerequisites for fixing the demonstrated correctness and reliability risks.

There is no requirement to add microservices, distributed money locks, asynchronous wallet balance writes, partial refunds, extra currencies or a new identity-provider product. Do not expand the scope into those areas.

## Completion criteria

The remediation is complete only when:

1. The original mandatory and supporting requirements continue to pass with the confirmed product policies.
2. Growing wallet histories no longer cause full-history work on each posting, and every previous money invariant remains protected.
3. Runtime-role shadow tables cannot bypass the integrity functions, and a populated-schema upgrade is verified.
4. Transfer responses and stored replays respect wallet privacy; real endpoint authorization and configured JWT checks are exercised.
5. Errors are actionable and correctly distinguish business rejection, transient failure and internal invariant failure.
6. Kafka acknowledgement durability, poison recovery, relay ownership and consumer replay have appropriate automated or deployment-level evidence.
7. Promotion, debit, duplicate request, transfer and refund races protect balances and entitlements; failure tests assert all related records.
8. Java 21 `clean verify -Pmutation`, formatting checks and packaging pass with meaningful reports; SQL invariants have direct database tests.
9. Production configuration and backup/restore prerequisites are documented and verified for the intended deployment before describing that deployment as production-ready.

No implementation work is authorized by this document itself. The current request is to fact-check and update documentation/memory only; later user instructions determine implementation scope.
