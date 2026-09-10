---
document_type: independent_code_review
subject: wallet-ledger-service
repository_path: /Users/harvey/Projects/wallet_ledger_backend_service
stack: Java 21, Spring Boot 3.5.16, Spring JDBC (JdbcClient), PostgreSQL 17.6, Flyway, Redis 7.4.5, Kafka 3.9.1, Testcontainers, PIT
size: 77 files; 2036 lines main Java; 3485 lines test Java; 452 lines SQL
reviewer: Claude (Anthropic), senior system analyst; no application code, SQL, test or build file was modified in either pass
pass_1: 4a1a954 on codex/implement-wallet-ledger, 2026-09-09
pass_2: 9ef2639 on main, 2026-09-10, after the ledger integrity fix
fact_check: Codex, 2026-09-10, at 2fe9ce4; documentation only, no fresh application runs
verdict: CORE_FEATURES_IMPLEMENTED; V4 fixes F1, but production readiness is not established
open: F2-F8 and input clarity remain; receipt privacy, messaging and deployment gates also apply
scores:
  money_moving_correctness: STRONG
  service_design: GOOD
  concurrency_and_edge_cases: STRONG
  test_quality: GOOD_WITH_GAPS
  documentation: VERY_GOOD
---

# Independent Review: Wallet Ledger Service

Two passes without application-source edits. Evidence tags: `STATIC` (source), `BUILD` (my own test run), `LIVE` (HTTP probe against the compose stack), `BENCH` (throwaway PostgreSQL), `DB` (direct SQL).

**Fact-check notice, 2026-09-10:** Codex checked this report at `2fe9ce4`, whose only change after `9ef2639` is this review. The original review is preserved in that commit; corrections below do not attribute new runs to Claude. First-person statements and BUILD/LIVE/BENCH/DB observations refer to Claude's historical passes. This check inspected source, existing local reports and primary documentation; it ran no application tests, benchmarks, HTTP probes or database commands. See the [current fact check and evidence limits](review-remediation-plan.md#second-pass-fact-check--2026-09-10).

## Verdict

The core features are implemented. V4 repairs quadratic commit-time validation and the TEMP-shadow bypass, with supporting regression and benchmark evidence. This does not establish production readiness or prove that no remaining defect can affect money movement. Transfer receipts still expose the recipient's balance, and topic durability, event validation/recovery, authorization coverage, error clarity and deployment controls remain open. See [R3–R6 and the remaining plan](review-remediation-plan.md#r3--p2-transfer-receipts-disclose-another-players-balance).

## Build facts

| Commit | Date | Result |
| --- | --- | --- |
| 4a1a954 | 2026-09-09 | BUILD SUCCESS in 26.7 s. 15 unit + 32 integration tests, 0 failures, errors or skips. A passing run does not establish absence of flakes. Spotless clean. PIT 19/19 killed. |
| 9ef2639 | 2026-09-10 | BUILD SUCCESS in 38.8 s. 15 unit + 90 integration cases, 0 failures, errors or skips. Spotless and packaging clean. PIT 19/19 killed. SQL mutation harness 12/12 killed. |

Both runs were reported to use `clean verify -Pmutation` on GraalVM JDK 21.0.8, matching the Maven goals/profile in the later CI workflow. CI was added at `79032c9`, after the first reviewed baseline. `NOT_VERIFIED`: neither pass confirmed a GitHub-hosted run; the reviewer reported a `gh` HTTP 404. This fact check did not repeat that lookup.

Existing local XML corroborates 15 unit/adapter tests, 90 integration cases (including 12 SQL mutation cases), zero failures/errors/skips and 19 killed PIT mutants. PIT targets four domain classes; the SQL harness is targeted, not exhaustive. These artifacts are mutable and were inspected, not regenerated. The repository size above is confirmed at this baseline; 452 SQL lines includes migrations, role bootstrap and the audit script.

## F1. SERIOUS, resolved at 9ef2639

**The defect.** V1's deferred trigger `check_wallet_ledger` re-derived the running balance of every entry a wallet had ever had, on every posting. It fired twice per single-wallet posting and four times per transfer. The 15 s `statement_timeout` did not cancel it, consistent with PostgreSQL disarming the statement timer before commit processing where deferred triggers run. The wallet row lock and the Hikari connection were held for the whole commit, so a few long-history wallets could drain the 30-connection pool for all players. 20,000 lifetime entries is roughly one year at 50 transactions per day.

One credit posting including the deferred COMMIT, median of three warmed samples, same host both times (BENCH):

| Existing entries in wallet | 4a1a954 | 9ef2639 |
| ---: | ---: | ---: |
| 1,000 | 71.7 ms | 1.9 ms |
| 5,000 | 1,511 ms | 1.8 ms |
| 10,000 | 6,067 ms | 1.5 ms |
| 20,000 | 24,106 ms | 1.9 ms |

The 3.97x growth ratio from 10k to 20k that identified the defect is gone; cost is flat within noise. Debit, transfer and refund behave the same. Local numbers on a throwaway database, not a production SLO.

**The fix.** `src/main/resources/db/migration/V4__indexed_ledger_integrity.sql` replaces the two trigger function bodies, leaving V1 to V3 and every constraint, index and trigger identity applied. Per new player entry it checks account ownership, then a zero base at `wallet_sequence = 1` or the predecessor row at `wallet_sequence - 1`, then `previous.balance_after + NEW.amount = NEW.balance_after` in `NUMERIC`, and finally compares the stored wallet row against the ledger tail. Both lookups ride the existing `UNIQUE(wallet_id, wallet_sequence)` index.

Two additions beyond what the first pass recommended, both worth keeping:

- A write-blocking preflight takes `SHARE ROW EXCLUSIVE` on the five ledger tables, audits all existing history in one snapshot, and aborts the upgrade with SQLSTATE `23514` on a finding within the audit's defined checks. This establishes the base for incremental sequence/running-balance validation; it does not prove every operation's business semantics (see N3).
- All four integrity functions move to qualified relation names with `search_path = pg_catalog, public, pg_temp` and invoker rights. This closes a real V1 bypass the first pass missed: a `wallet_app` session could shadow `wallet` and `ledger_entry` with temporary tables, and the unqualified function bodies would validate the shadows while permanent balance drift committed.

`public.audit_ledger_integrity()` and `scripts/audit-ledger.sql` supply the full maintenance audit as a bounded read-only job that never runs on the posting path. The repository supplies scheduling instructions; an external scheduler and alerts are not installed. The admin HTTP reconciliation endpoint still checks aggregate balances only.

The benchmark table retains Claude's samples: existing `target/ledger-history-v4.json` corroborates its rounded V4 medians. The [committed V3/V4 samples](ledger-integrity-v4.md#long-history-measurements) are separate runs and must not be relabeled as these measurements.

**Verification reported by Claude, 2026-09-10.** Full gate green as above (BUILD). `LedgerSqlMutationIT` kills 12/12 SQL mutants, each behind a passing green control, including mutants that strip the qualified names, the predecessor check, the tail check and the numeric arithmetic (BUILD). Benchmark flat within these samples, with `EXPLAIN` showing one row through the unique index at every size (BENCH); indexed lookup is not literally constant-time I/O. V4's audit query reportedly returned zero findings on the Compose database's V3 snapshot (DB). A later migration must validate the data again under its write-blocking locks; this observation is not a guaranteed future preflight result. Tests cover missing predecessors, a bad intermediate balance hidden behind a correct final total, a missing wallet update, ownership and metadata mismatches, `BIGINT` overflow, and multiple postings in one transaction (STATIC).

## Open findings

- **F2. MODERATE: incomplete HTTP-level authorization coverage.** STATIC, historical LIVE. `HttpApiIT` already tests anonymous rejection, another-wallet access and forbidden player credit. Only `/v1/players`, `/v1/wallets/{id}/credits`, `/debits`, `/balance` and `/transactions` are exercised over HTTP. Untested: `/v1/transfers`, `/v1/transactions/{id}/refunds`, `/v1/daily-login/claims`, `/v1/rewards/{id}/claims`, `/v1/promotions/{id}/claims`, `/internal/v1/action-completions`, `/v1/admin/reconciliation`. A regression letting SERVICE call `/v1/transfers` or PLAYER call `/refunds` would pass the suite. Live probes were correct on 2026-09-09. Add a MockMvc matrix over the real controllers plus configured JWT-decoder tests; `JwtSecurityTest` uses a fake decoder and probe controller.
- **F3. MODERATE: 24 of 35 error codes are never asserted by name.** STATIC. Asserted (11): BALANCE_LIMIT, COMPLETION_NOT_OWNED, DAILY_ALREADY_CLAIMED, FORBIDDEN, IDEMPOTENCY_KEY_REUSED, INSUFFICIENT_FUNDS, INVALID_AMOUNT, INVALID_INPUT, REWARD_LIMIT_EXCEEDED, SEQUENCE_LIMIT, UNAUTHENTICATED. Never asserted (24): ALREADY_REFUNDED, BUSINESS_REFERENCE_USED, COMPLETION_NOT_FOUND, COMPLETION_REFERENCE_REUSED, COMPLETION_REWARD_MISMATCH, DEPENDENCY_UNAVAILABLE, INVALID_COMPLETION_REFERENCE, INVALID_PAGINATION, INVALID_PLAYER_IDENTITY, NOT_FOUND, PLAYER_EXISTS, PLAYER_NOT_FOUND, PLAYER_SUSPENDED, PROMOTION_ALREADY_CLAIMED, PROMOTION_DISABLED, PROMOTION_EXHAUSTED, PROMOTION_NOT_FOUND, RATE_LIMITED, REFUND_NOT_SUPPORTED, REWARD_ALREADY_CLAIMED, REWARD_DISABLED, REWARD_NOT_FOUND, SELF_TRANSFER, TRANSACTION_NOT_FOUND. No focused missing-player test for credit, debit or balance was found. The 35 emitted codes and 11 named assertions were re-counted from source; this measures literal assertions, not branch or behavior coverage.
- **F4. MODERATE: every DataAccessException maps to 503.** STATIC, `ApiProblems.java:30-38`. Every such exception reaching this handler is reported as a retryable dependency outage with `Retry-After: 1`; pre-checks do not prove invariant or programming failures unreachable. Map only recognized business conflicts to 409, retryable infrastructure failures to 503, and unexpected invariant/programming failures to 500 with observability. A blanket integrity-to-409 mapping would hide defects. [Spring exception hierarchy](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/dao/DataIntegrityViolationException.html).
- **F5. MINOR: promotion claims lock the campaign before duplicate/exhaustion checks.** STATIC, `RewardService.java:98-131`. `requirePlayer` checks existence/active status before the lock. Already-claimed and post-exhaustion requests still queue on the single `promotion` row, potentially causing 5 s lock timeouts and 503s under contention for requests that would be rejected anyway. Read unlocked first, reject early, take `FOR UPDATE` only for plausible winners, keep the locked re-check for correctness.
- **F6. MINOR: history rows omit refund origin and transfer counterparty.** LIVE, `WalletService.java:216-262`. The data exists in `journal_transaction.original_transaction_id` and the paired entry; the API does not surface it. Reduces auditability.
- **F7. MINOR: inconsistent problem+json envelopes.** LIVE. Stored rejections use `title = code` and omit `instance`; framework errors use `title = "Bad Request"` and include it. The `code` field is stable in both paths. An absent `instance` is not itself noncompliance; it is optional in [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html).
- **F8. MINOR: unverifiable process claims, partially addressed.** STATIC. One squashed implementation commit means the TDD narrative in docs/build-evidence.md cannot be corroborated from history, though its numeric results reproduced exactly. The V4 work does record a specific red state and green state; I reproduced the green state, and the red state is attributed rather than re-run.
- **Invalid-input clarity: PARTIAL.** LIVE. Validation is safe, but every bean-validation failure returns the same generic body with no field name or reason. Negative amount, missing field, unknown field and malformed UUID all returned the identical 400.

## Notes from the second pass, with corrected scope

- **N1. Pending V4 applies at startup and blocks writers while it audits.** `application.yml` configures Flyway's own datasource, so Hikari's connection-init timeouts do not apply to migration connections. Boot checks migrations on startup; an already-applied V4 is not rerun. Preflight locks permit ordinary SELECT and ROW SHARE table locks but conflict with the ROW EXCLUSIVE locks required by writes. If old instances keep writing, requests can time out; `CommandExecutor` allows **three attempts total (two retries)** for translated transient failures. A rolling-deploy outage was not reproduced. The repo does not set migration-session lock/statement/transaction limits; actual server/role limits may still apply. Treat a controlled upgrade as a production gate: quiesce writers, apply/validate migrations in a separate deployment step with measured limits, and disable startup Flyway on serving instances only once that step is enforced. The repository does not implement this deployment process. [Boot 3.5 initialization](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html), [PostgreSQL lock modes](https://www.postgresql.org/docs/17/explicit-locking.html).
- **N2. The long-history regression bound is deliberately generous.** `LongHistoryIT` allows 10 s for a credit at 20,000 entries; it detects the observed quadratic regression, not every performance regression. `LedgerHistoryMeasurement` captures candidate lookup plans but does not assert plan shape. Keep the service-through-commit deadline and consider supplemental representative plan/index checks; standalone query plans alone cannot prove the trigger still uses those queries. No dropped-index regression was reproduced, and the current index backs a uniqueness constraint.
- **N3. Database checks do not enforce all per-operation entry semantics.** `check_complete_journal` enforces two distinct balanced accounts and header amount/currency. It does not require CREDIT to increase a player against issuance, DEBIT to decrease a player against purchase, or TRANSFER to join two players. A funded player's negative CREDIT against purchase, or a TRANSFER against issuance, can satisfy the inspected constraints if the rest of the journal/chain is valid; these are static SQL counterexamples, not newly executed exploits. `refund_inverse` is an **audit finding**, not an insert/commit trigger: even inverse-refund semantics are not fully enforced at commit. `WalletService` builds these correctly. Adding audit checks supplies detection, not prevention; any later enforcement needs a new migration and real PostgreSQL acceptance tests. [V1 constraints](../src/main/resources/db/migration/V1__ledger.sql), [V4 functions](../src/main/resources/db/migration/V4__indexed_ledger_integrity.sql).
- **N4. TEMP privilege is retained on `wallet_app`.** V4 qualifies permanent names and pins a trusted search path; regressions exercise retained TEMP access. Removing TEMP is optional defence in depth, not a demonstrated free one-line fix. PostgreSQL grants database TEMP to `PUBLIC` by default, so revoking only from `wallet_app` does not remove inherited access. Review PUBLIC, direct and membership grants and legitimate users before tightening privileges. [PostgreSQL privileges](https://www.postgresql.org/docs/17/ddl-priv.html), [REVOKE semantics](https://www.postgresql.org/docs/17/sql-revoke.html).

## Design notes, not graded as findings

- `Map<String, Object>` is the universal return type for receipts, balances and history; `RewardService.transactionId` parses a UUID back out of a map by `toString()`. No typed DTOs, no OpenAPI.
- `BusinessException` carries HTTP status codes and lives in `wallet.domain` but is shared by rewards, idempotency and configuration.
- `WalletService` (~490 lines) does validation, locking, posting, outbox serialization, history and reconciliation. The rewards side has a repository split; the wallet side does not.
- Mixed SQL style: named parameters and lowercase keywords in `WalletService`, positional `?` and uppercase in `RewardRepository`.
- `player.status = 'SUSPENDED'` is checked when locking wallets and before reward work. There is no suspension-management API and `wallet_app` lacks UPDATE on player, but its INSERT grant permits an explicitly suspended new player; the migration owner can update status. The guard is not demonstrably vestigial.
- Rate limiter fails open when Redis is down, documented and metered. Acceptable since PostgreSQL is authoritative.
- Retryable listener failures use unlimited retry with 1 s backoff; malformed JSON is wrapped in `IllegalArgumentException` and can block progress. Spring Kafka has default fatal classifications, so not every failure follows this retry path. Durable quarantine/recovery and real listener/offset tests remain pending. [Spring Kafka error handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).
- `RewardRacesIT.fiveHundredPlayersCompete...` runs 500 virtual threads against a 30-connection pool with a 5 s connection timeout. It passed in 4.8 s here but could flake on a slow CI host. Low risk.

## TODO, in priority order

1. ~~F1: replace the quadratic trigger, add a long-history test, re-run the benchmark, update the README trade-off text.~~ Done at 9ef2639, verified 2026-09-10. The numbers live in docs/ledger-integrity-v4.md rather than the README itself.
2. N1: deploy V4 as an explicit pre-deploy migration step, before the next production upgrade.
3. F2: HTTP authorization matrix over every mutation endpoint and reconciliation.
4. F3: assert error codes by name, starting with PLAYER_NOT_FOUND, SELF_TRANSFER, REFUND_NOT_SUPPORTED, ALREADY_REFUNDED, PLAYER_EXISTS, the promotion codes and INVALID_PAGINATION.
5. F4: classify known business conflicts as 409, transient outages as 503, and unexpected integrity/programming failures as 500.
6. F5: unlocked eligibility pre-check before locking the promotion row.
7. Invalid-input clarity: include field name and violation in 400 bodies, keeping one stable `code`.
8. F6: add `originalTransactionId` and transfer counterparty to history rows.
9. N3 and N2: consider operation-semantic checks and supplemental plan/index checks while retaining the wall-clock regression. Distinguish audit detection from commit enforcement.
10. Design hygiene: typed receipt records, move `BusinessException` to a shared package, one SQL style. Review effective TEMP grants separately if tightening runtime privileges.

This original list is incomplete for production: [step 2](review-remediation-plan.md#step-2-close-transfer-disclosure-and-prove-api-authorization) also requires caller-safe fresh/stored transfer receipts, and [step 4](review-remediation-plan.md#step-4-make-event-delivery-suitable-for-production) covers durable Kafka topics, strict event validation and recovery. The fact-checked plan owns the full remaining scope.

## Side effects on the local environment

**2026-09-09.** `target/` was rebuilt, replacing the author's 2026-09-08 reports with identical results. The compose demo database moved Alice (`10000000-0000-0000-0000-000000000001`) from 215/7 to 226/9 via one 1-unit credit and one daily claim, and gained 10 `idempotency_request` rows keyed `claude-verify-`. Existing wallet balances/sequences were updated and new ledger records appended; existing journal/entry history was not rewritten, so a later `scripts/demo.py` run gets a tolerated 409 on the daily claim. The benchmark container was removed.

**2026-09-10.** `target/` was rebuilt twice, by `clean verify -Pmutation` and by `LedgerHistoryMeasurement`, which also wrote `target/ledger-history-v4.json`. All test containers were disposable and are gone. The compose demo database was read only: one `REPEATABLE READ READ ONLY` transaction ran V4's audit query against it. It remains at schema version 3 with Alice at 226/9. This review document is the only repository file Claude reported editing in either pass. The subsequent Codex fact check updates this report and related documentation/memory only; it does not recheck the live Compose state.
