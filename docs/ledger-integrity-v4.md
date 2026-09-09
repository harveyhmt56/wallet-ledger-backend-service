# Ledger integrity V4: operation and evidence

Checked 2026-09-09 on baseline `79032c9` plus the V4 remediation change. Java 21.0.8 / Spring Boot 3.5.16 remain unchanged. This implements step 1 of the [remediation plan](review-remediation-plan.md#step-1-preserve-behaviour-and-repair-database-safeguards); later remediation steps remain open.

## Database boundary

[V4](../src/main/resources/db/migration/V4__indexed_ledger_integrity.sql) preserves applied V1–V3 and their constraints, indexes and trigger identities. Every inserted player entry validates its account, sequence-1 zero base or indexed predecessor, and numeric running balance. Both entry and wallet triggers compare the **final stored wallet** to the final indexed ledger tail. Empty wallets require zero balance/sequence. Multiple postings in one transaction work; an invalid intermediate balance cannot hide behind a correct final total.

This is an inductive check: the migration audits existing history, each new entry extends a valid chain, and history remains immutable. The existing unique `(wallet_id, wallet_sequence)` index supplies the predecessor point lookup and backward tail scan. There is no historical sum on the posting path; journal checks still inspect the two entries through the existing transaction/account index. Indexed lookups grow with index height; this is not a claim of literal constant-time I/O. See PostgreSQL's [B-tree ordering and LIMIT](https://www.postgresql.org/docs/17/indexes-ordering.html).

All four integrity/audit functions use invoker privileges and `search_path = pg_catalog, public, pg_temp`. Persistent relations and composite row types are explicitly qualified. The fix works while `wallet_app` still has TEMP privilege, as the regressions demonstrate. No `SECURITY DEFINER` privilege was introduced. PostgreSQL explains [temporary-schema precedence and trusted function search paths](https://www.postgresql.org/docs/17/sql-createfunction.html#SQL-CREATEFUNCTION-SECURITY).

## Populated upgrades and operational audit

V4 takes `SHARE ROW EXCLUSIVE` locks on player, account, wallet, journal and entry tables, runs preflight, and replaces functions in the same Flyway transaction. Writers cannot change the audited base before installation commits. Plan a maintenance window with writers quiesced: preflight scans all history and may wait behind active writers. A failed preflight raises SQLSTATE `23514`, naming an issue and entity; the migration rolls back and V3/data remain unchanged. Investigate the source of corruption and preserve evidence; do not skip preflight or rewrite ledger history. The waiting-writer test proves that V4 audits an in-flight writer's committed data. See PostgreSQL's [table lock modes](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-TABLES).

`public.audit_ledger_integrity()` is a separate read-only SQL function. One statement uses one snapshot and window `SUM`/`LAG`, checking ownership, metadata, sequence continuity, running balances, wallet totals, journal completeness and inverse full credit/debit refunds. These checks supplement the schema's existing foreign keys, checks and uniqueness constraints. It returns `(issue, entity_id)` rows; no rows means no findings. See [window frames](https://www.postgresql.org/docs/17/functions-window.html) and [STABLE function snapshots](https://www.postgresql.org/docs/17/xfunc-volatility.html).

Run the bounded [maintenance script](../scripts/audit-ledger.sql) using an existing libpq service definition for `wallet_app` (or a read-only operator granted SELECT on the audited tables and EXECUTE on the function):

```sh
psql -X 'service=wallet-ledger-audit' --set=ON_ERROR_STOP=1 --file=scripts/audit-ledger.sql
```

The service configuration supplies the intended database and authentication outside source control. The script uses a read-only repeatable-read transaction, a five-minute statement limit and six-minute transaction limit. It logs success or raises an error with finding count/example. Tests exercise the actual PostgreSQL 17 `psql` command: valid history exits 0; corruption exits 3. For detail, run `SELECT * FROM public.audit_ledger_integrity()` in a similarly bounded read-only transaction.

Operational schedule: configure the deployment's existing job runner to execute this command daily at 03:00 UTC and after recovery/import. Route **any nonzero exit**, including timeout or connection failure, to the database on-call alert and retain stderr. Tune the maintenance limits only from measured audit size. No external scheduler or alert destination is installed by this repository. The existing admin reconciliation HTTP endpoint remains an aggregate balance check; the SQL audit is the full maintenance check.

## Observed red → green

- Before V4, `LedgerIntegrityIT` ran 19 cases: four expected-throw failures reproduced TEMP wallet/ledger balance drift, fake ledger journal completion, substituted journal header amount, and hidden player metadata. The remaining 15 passed. Transactions used fresh runtime-role sessions and real PostgreSQL, not an HTTP exploit.
- Before V4, a credit on 20,000 existing entries took **25.692 seconds**, failing the CI regression's ten-second bound. The bound measures a service call through actual JDBC commit; it is deliberately generous and is not a production SLO.
- Before V4, 20 upgrade/audit cases produced 11 assertion failures (missing V4 and corrupt upgrades accepted) plus nine missing-function errors. The corrupt-upgrade assertions establish the preflight red state; the missing-function errors alone are not integrity evidence.
- With V4, the tests reject direct drift, missing wallet updates, bad first/intermediate balances, gaps, ownership/metadata mismatches, incomplete journals and overflow. They allow valid BIGINT-boundary balances and multiple postings in one outer transaction. Populated V3 upgrades preserve records; nine corruption fixtures fail preflight and are found by the operational audit. Audit TEMP shadowing, write-lock handoff and actual CLI exit codes are covered.
- `LongHistoryIT` tests two 20,000-entry wallets, all four posting operations, then 16 concurrent credit/debit/transfer/refund requests; independent window/aggregate checks verify the resulting history.
- `TransactionDeadlineIT` injects a deferred trigger delay: `statement_timeout=100ms` cancels ordinary `pg_sleep` but permits a roughly 600ms deferred commit. `transaction_timeout=500ms` terminates an injected two-second deferred commit with `25P04`; money, journal, outbox and reservation roll back, and the same key retries/replays correctly. A JDBC proxy separately drops the acknowledgement **after a real successful commit**, then verifies stored-receipt recovery without a second posting. This is fault injection, not a real network partition.

`transaction_timeout` is a tested PostgreSQL 17 safeguard, not enabled globally by this change. The existing Spring transaction timeout and statement timeout must not be described as commit deadlines. Deployments can set an agreed per-role/session transaction limit after checking pool reconnection and workload duration. A lost commit response remains uncertain until recovered with the same idempotency key. See [PostgreSQL timeout semantics](https://www.postgresql.org/docs/17/runtime-config-client.html) and the [17.6 commit timeout implementation](https://raw.githubusercontent.com/postgres/postgres/REL_17_6/src/backend/tcop/postgres.c).

## Long-history measurements

Local ARM64 macOS / OrbStack, PostgreSQL 17.6 Alpine aarch64, Java 21.0.8, 10 reported JVM processors. Five short-history cycles warmed credit/debit/transfer/refund before measuring each fresh pair of long-history wallets. Fixtures were loaded with **session-local** trigger suppression by the administrator only inside disposable test databases; triggers were restored and independent audits ran before measurements. No Compose or deployed database was used.

Times below are milliseconds for `WalletService` calls including SQL and JDBC commit, excluding HTTP, Redis, Kafka, fixture loading and audits. V3 has one warmed credit sample per size; V4 values are medians of three samples per operation. These small local samples show removal of the quadratic curve, not production capacity or tail-latency guarantees.

| Existing entries per wallet | V3 credit | V4 credit | V4 debit | V4 transfer | V4 refund |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 74.926 | 1.667 | 1.529 | 2.091 | 2.011 |
| 5,000 | 1,519.784 | 1.573 | 1.392 | 1.934 | 1.906 |
| 10,000 | 6,003.796 | 1.374 | 1.503 | 1.835 | 1.970 |
| 20,000 | 24,930.376 | 1.685 | 1.613 | 2.022 | 2.102 |

At every size the predecessor query used `ledger_entry_wallet_id_wallet_sequence_key` and returned one row; the tail used a backward scan of that same index under `LIMIT 1`, returning one row with no rows removed by a filter. Retained [V3 raw samples/plans](evidence/ledger-history-v3.json) and [V4 raw samples/plans](evidence/ledger-history-v4.json) include timestamps, buffers and all samples. The V3 report's plans describe the candidate predecessor/tail queries, not V3's old trigger execution plan.

```sh
# Explicit benchmarks, excluded from ordinary test discovery:
./mvnw -Dtest=LedgerHistoryMeasurement -Dledger.history.target=3 \
  -Dledger.history.credit-only=true -Dledger.history.samples=1 test
./mvnw -Dtest=LedgerHistoryMeasurement test
# Focused acceptance / SQL mutation suite:
./mvnw test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=LedgerIntegrityIT,V4MigrationIT,LongHistoryIT,TransactionDeadlineIT,LedgerSqlMutationIT
# Required completion gate (Java 21 and Docker):
./mvnw --batch-mode --no-transfer-progress clean verify -Pmutation
```

## Verification scope

The full gate reports **15 unit/adapter tests, 90 integration cases, zero failures/errors/skips**; packaging and Spotless pass. PIT evaluates **19 mutants, all killed (100%)**, with zero survivors, uncovered, timed-out or errored results. Its four domain classes remain the same; this is not service-wide coverage.

`LedgerSqlMutationIT` additionally evaluates **12 targeted SQL mutants, all killed (100%)**, using an isolated schema and the same integrity acceptance examples. Each example passes an unmodified control before the mutant is installed; invalid edits, setup errors, survivors or unexpected infrastructure failures fail the harness. Mutants remove metadata/ownership/predecessor/tail checks, change first/intermediate/numeric arithmetic, compare intermediate wallet images, remove journal/header protections, restore unsafe name resolution or allow history updates. This complements PIT; it is not exhaustive automatic mutation coverage of every SQL/audit branch.

Fresh final machine-readable reports are under `target/surefire-reports`, `target/failsafe-reports`, and `target/pit-reports`; CI already retains them. Local full-gate log: `/private/tmp/ledger-v4-final-verify.log`. Earlier red logs: `/private/tmp/ledger-integrity-v3-red-final.log`, `/private/tmp/v4-upgrade-red.log`, `/private/tmp/wallet-history-v3-red.log`. These temporary paths are ephemeral. This document and retained benchmark JSON record observations; GitHub-hosted execution, deployment upgrades and production load were not performed.
