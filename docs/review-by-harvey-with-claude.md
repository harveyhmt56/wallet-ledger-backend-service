---
document_type: independent_code_review
subject: wallet-ledger-service
repository_path: /Users/harvey/Projects/wallet_ledger_backend_service
stack: Java 21, Spring Boot 3.5.16, Spring JDBC (JdbcClient), PostgreSQL 17.6, Flyway, Redis 7.4.5, Kafka 3.9.1, Testcontainers, PIT
size: 77 files; 2036 lines main Java; 3485 lines test Java; 452 lines SQL
reviewer: Claude (Anthropic), senior system analyst; no application code, SQL, test or build file was modified in either pass
pass_1: 4a1a954 on codex/implement-wallet-ledger, 2026-09-09
pass_2: 9ef2639 on main, 2026-09-10, after the ledger integrity fix
verdict: MEETS_ALL_REQUIREMENTS; the one serious defect is fixed and independently verified
open: 0 serious, 3 moderate, 5 minor, 4 non-blocking notes
scores:
  money_moving_correctness: STRONG
  service_design: GOOD
  concurrency_and_edge_cases: STRONG
  test_quality: GOOD_WITH_GAPS
  documentation: VERY_GOOD
---

# Independent Review: Wallet Ledger Service

Two read-only passes. Evidence tags: `STATIC` (source), `BUILD` (my own test run), `LIVE` (HTTP probe against the compose stack), `BENCH` (throwaway PostgreSQL), `DB` (direct SQL).

## Verdict

The service meets its requirements. The one serious defect from the first pass, a quadratic commit-time reconciliation trigger, is fixed by V4 and verified. Nothing open threatens the correctness of money movement. The remaining gaps are test coverage at the HTTP boundary, error classification, and API auditability.

## Build facts

| Commit | Date | Result |
| --- | --- | --- |
| 4a1a954 | 2026-09-09 | BUILD SUCCESS in 26.7 s. 15 unit + 32 integration tests, 0 failures, errors, skips or flakes. Spotless clean. PIT 19/19 killed. |
| 9ef2639 | 2026-09-10 | BUILD SUCCESS in 38.8 s. 15 unit + 90 integration cases, 0 failures, errors or skips. Spotless and packaging clean. PIT 19/19 killed. SQL mutation harness 12/12 killed. |

Both runs used the exact CI command on GraalVM JDK 21.0.8. `NOT_VERIFIED`: no GitHub-hosted CI run was confirmed for either commit, because `gh` returns HTTP 404 for this repository from this machine.

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

- A write-blocking preflight takes `SHARE ROW EXCLUSIVE` on the five ledger tables, audits all existing history in one snapshot, and aborts the upgrade with SQLSTATE `23514` on any inconsistency. This is what makes the induction valid: a verified base, an immutable history, and every new entry provably extending a valid chain.
- All four integrity functions move to qualified relation names with `search_path = pg_catalog, public, pg_temp` and invoker rights. This closes a real V1 bypass the first pass missed: a `wallet_app` session could shadow `wallet` and `ledger_entry` with temporary tables, and the unqualified function bodies would validate the shadows while permanent balance drift committed.

`public.audit_ledger_integrity()` and `scripts/audit-ledger.sql` supply the periodic full reconciliation as a bounded read-only job that never runs on the posting path.

**Verification, 2026-09-10.** Full gate green as above (BUILD). `LedgerSqlMutationIT` kills 12/12 SQL mutants, each behind a passing green control, including mutants that strip the qualified names, the predecessor check, the tail check and the numeric arithmetic (BUILD). Benchmark flat, with `EXPLAIN` showing one row through the unique index at every size (BENCH). V4's audit query run read-only against the live compose demo database returned zero findings on its V3 data, so that database would pass preflight (DB). Tests cover missing predecessors, a bad intermediate balance hidden behind a correct final total, a missing wallet update, ownership and metadata mismatches, `BIGINT` overflow, and multiple postings in one transaction (STATIC).

## Open findings

- **F2. MODERATE: no HTTP-level authorization tests.** STATIC, LIVE. Only `/v1/players`, `/v1/wallets/{id}/credits`, `/debits`, `/balance` and `/transactions` are exercised over HTTP. Untested: `/v1/transfers`, `/v1/transactions/{id}/refunds`, `/v1/daily-login/claims`, `/v1/rewards/{id}/claims`, `/v1/promotions/{id}/claims`, `/internal/v1/action-completions`, `/v1/admin/reconciliation`. A regression letting SERVICE call `/v1/transfers` or PLAYER call `/refunds` would pass the suite. Live probes were correct on 2026-09-09. Fix with a MockMvc endpoint-by-role matrix.
- **F3. MODERATE: 24 of 35 error codes are never asserted by name.** STATIC. Asserted (11): BALANCE_LIMIT, COMPLETION_NOT_OWNED, DAILY_ALREADY_CLAIMED, FORBIDDEN, IDEMPOTENCY_KEY_REUSED, INSUFFICIENT_FUNDS, INVALID_AMOUNT, INVALID_INPUT, REWARD_LIMIT_EXCEEDED, SEQUENCE_LIMIT, UNAUTHENTICATED. Never asserted (24): ALREADY_REFUNDED, BUSINESS_REFERENCE_USED, COMPLETION_NOT_FOUND, COMPLETION_REFERENCE_REUSED, COMPLETION_REWARD_MISMATCH, DEPENDENCY_UNAVAILABLE, INVALID_COMPLETION_REFERENCE, INVALID_PAGINATION, INVALID_PLAYER_IDENTITY, NOT_FOUND, PLAYER_EXISTS, PLAYER_NOT_FOUND, PLAYER_SUSPENDED, PROMOTION_ALREADY_CLAIMED, PROMOTION_DISABLED, PROMOTION_EXHAUSTED, PROMOTION_NOT_FOUND, RATE_LIMITED, REFUND_NOT_SUPPORTED, REWARD_ALREADY_CLAIMED, REWARD_DISABLED, REWARD_NOT_FOUND, SELF_TRANSFER, TRANSACTION_NOT_FOUND. `PLAYER_NOT_FOUND` on credit, debit and balance has no test at all.
- **F4. MODERATE: every DataAccessException maps to 503.** STATIC, `ApiProblems.java:30-38`. A `DataIntegrityViolationException` is reported as a retryable dependency outage with `Retry-After: 1`. Advisory locks and pre-checks make this effectively unreachable today, so it is latent. Map it to 409 and keep 503 for `TransientDataAccessException`.
- **F5. MINOR: promotion claims lock the campaign row before any eligibility check.** STATIC, `RewardService.java:98-131`. Already-claimed and post-exhaustion requests still queue on the single `promotion` row, turning a viral promotion into 5 s lock timeouts and 503s for requests that would be rejected anyway. Read unlocked first, reject early, take `FOR UPDATE` only for plausible winners, keep the locked re-check for correctness.
- **F6. MINOR: history rows omit refund origin and transfer counterparty.** LIVE, `WalletService.java:216-262`. The data exists in `journal_transaction.original_transaction_id` and the paired entry; the API does not surface it. Reduces auditability.
- **F7. MINOR: inconsistent problem+json envelopes.** LIVE. Stored rejections use `title = code` and omit `instance`; framework errors use `title = "Bad Request"` and include it. The `code` field is stable in both paths, which is what clients should key on.
- **F8. MINOR: unverifiable process claims, partially addressed.** STATIC. One squashed implementation commit means the TDD narrative in docs/build-evidence.md cannot be corroborated from history, though its numeric results reproduced exactly. The V4 work does record a specific red state and green state; I reproduced the green state, and the red state is attributed rather than re-run.
- **Invalid-input clarity: PARTIAL.** LIVE. Validation is safe, but every bean-validation failure returns the same generic body with no field name or reason. Negative amount, missing field, unknown field and malformed UUID all returned the identical 400.

## Notes from the second pass, none blocking

- **N1. V4 runs at instance startup and blocks writers while it audits.** `spring.flyway` is configured and not disabled, so Spring Boot applies V4 on boot. The preflight's `SHARE ROW EXCLUSIVE` locks conflict with the `ROW EXCLUSIVE` every posting takes; reads are unaffected. In a rolling deploy the old instances keep serving, their writes queue, hit the 5 s `lock_timeout` from `connection-init-sql`, get retried three times by `CommandExecutor` and surface as 503. Flyway uses its own datasource and does not inherit that timeout, so the migration waits as long as the audit needs. docs/ledger-integrity-v4.md asks for a maintenance window; nothing in the deployment path enforces one. Run Flyway as an explicit pre-deploy step with writers quiesced and set `spring.flyway.enabled=false` on serving instances.
- **N2. The long-history regression bound is very loose.** `LongHistoryIT.java:39` allows 10 s for a credit at 20,000 entries against a measured 2 ms. It catches a return to quadratic behaviour but not, say, a dropped index turning the tail lookup into a sequential scan. `LedgerHistoryMeasurement` already captures the plans; asserting plan shape would be tighter.
- **N3. The database does not enforce per-operation entry semantics.** Neither the triggers nor `audit_ledger_integrity()` check that an operation's entries match the operation. `journal_complete` requires two distinct accounts, equal and opposite amounts and a matching header, and `refund_inverse` is the only operation-specific rule. A CREDIT whose player entry is negative against the purchase account, or a TRANSFER between a player and issuance, passes every current check. `WalletService` builds these correctly, so this is defence in depth, and it is cheap to add to the audit function.
- **N4. TEMP privilege is retained on `wallet_app`.** V4 relies on name qualification alone and documents that choice, and the mutation harness shows qualification is what does the work. Reasonable as designed; a one-line `REVOKE TEMP` would still cost nothing.

## Design notes, not graded as findings

- `Map<String, Object>` is the universal return type for receipts, balances and history; `RewardService.transactionId` parses a UUID back out of a map by `toString()`. No typed DTOs, no OpenAPI.
- `BusinessException` carries HTTP status codes and lives in `wallet.domain` but is shared by rewards, idempotency and configuration.
- `WalletService` (~490 lines) does validation, locking, posting, outbox serialization, history and reconciliation. The rewards side has a repository split; the wallet side does not.
- Mixed SQL style: named parameters and lowercase keywords in `WalletService`, positional `?` and uppercase in `RewardRepository`.
- `player.status = 'SUSPENDED'` is checked on every lock, but no code path or grant can set it. Vestigial.
- Rate limiter fails open when Redis is down, documented and metered. Acceptable since PostgreSQL is authoritative.
- Kafka consumer retries indefinitely with 1 s backoff, so one poison record blocks its partition. Documented as a limitation.
- `RewardRacesIT.fiveHundredPlayersCompete...` runs 500 virtual threads against a 30-connection pool with a 5 s connection timeout. It passed in 4.8 s here but could flake on a slow CI host. Low risk.

## TODO, in priority order

1. ~~F1: replace the quadratic trigger, add a long-history test, re-run the benchmark, update the README trade-off text.~~ Done at 9ef2639, verified 2026-09-10. The numbers live in docs/ledger-integrity-v4.md rather than the README itself.
2. N1: deploy V4 as an explicit pre-deploy migration step, before the next production upgrade.
3. F2: HTTP authorization matrix over every mutation endpoint and reconciliation.
4. F3: assert error codes by name, starting with PLAYER_NOT_FOUND, SELF_TRANSFER, REFUND_NOT_SUPPORTED, ALREADY_REFUNDED, PLAYER_EXISTS, the promotion codes and INVALID_PAGINATION.
5. F4: map `DataIntegrityViolationException` to 409.
6. F5: unlocked eligibility pre-check before locking the promotion row.
7. Invalid-input clarity: include field name and violation in 400 bodies, keeping one stable `code`.
8. F6: add `originalTransactionId` and transfer counterparty to history rows.
9. N3 and N2: audit per-operation entry semantics; assert the query plan instead of a wall-clock bound. Can travel with any later database work.
10. Design hygiene: typed receipt records, move `BusinessException` to a shared package, one SQL style. N4 (`REVOKE TEMP`) fits here.

## Side effects on the local environment

**2026-09-09.** `target/` was rebuilt, replacing the author's 2026-09-08 reports with identical results. The compose demo database moved Alice (`10000000-0000-0000-0000-000000000001`) from 215/7 to 226/9 via one 1-unit credit and one daily claim, and gained 10 `idempotency_request` rows keyed `claude-verify-`. Nothing existing was modified; the ledger is append-only, so a later `scripts/demo.py` run gets a tolerated 409 on the daily claim. The benchmark container was removed.

**2026-09-10.** `target/` was rebuilt twice, by `clean verify -Pmutation` and by `LedgerHistoryMeasurement`, which also wrote `target/ledger-history-v4.json`. All test containers were disposable and are gone. The compose demo database was read only: one `REPEATABLE READ READ ONLY` transaction ran V4's audit query against it. It remains at schema version 3 with Alice at 226/9. This review document is the only repository file either pass edited.
