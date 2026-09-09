---
document_type: independent_code_review
subject: wallet-ledger-service
repository_path: /Users/harvey/Projects/wallet_ledger_backend_service
commit: 4a1a954
branch: codex/implement-wallet-ledger
review_date: 2026-09-09
reviewer: Claude (Anthropic), acting as senior system analyst; code was NOT modified during review
stack: Java 21, Spring Boot 3.5.16, Spring JDBC (JdbcClient), PostgreSQL 17.6, Flyway, Redis 7.4.5, Kafka 3.9.1, Testcontainers, PIT
size: 57 files; 2036 lines main Java; 1789 lines test Java; 245 lines SQL
overall_verdict: MEETS_ALL_REQUIREMENTS_WITH_ONE_SERIOUS_DEFECT
serious_defects: 1
moderate_findings: 3
minor_findings: 5
build_reproduced: true
build_result: BUILD SUCCESS in 26.7 s; 15 unit tests + 32 integration tests, 0 failures, 0 errors, 0 skipped, 0 flakes; Spotless clean; PIT 19/19 mutants killed
scores:
  money_moving_correctness: STRONG_WITH_ONE_SCALE_DEFECT
  service_design: GOOD
  concurrency_and_edge_cases: STRONG
  test_quality: GOOD_WITH_GAPS
  documentation: VERY_GOOD_ONE_UNDERSTATED_RISK
---

# Independent Review: Wallet Ledger Service

## How to read this document

- Every claim carries an evidence tag: `STATIC` (read from source), `BUILD` (from my own test run), `LIVE` (HTTP probe against the running compose stack), `BENCH` (measured on a throwaway PostgreSQL), `DB` (direct SQL inspection of the running stack).
- Status enums: `MET`, `PARTIAL`, `NOT_MET`, `VERIFIED`, `NOT_VERIFIED`.
- File references are repository-relative with line numbers.

## Requirement should be met

### Safety and correctness

| Requirement | Status | Mechanism | Evidence |
| --- | --- | --- | --- |
| Invalid inputs handled clearly | PARTIAL | Safe: `@NotNull @Positive Long amount`, strict Jackson (no float-as-int, no scalar coercion, unknown properties rejected), service-level re-validation, DB CHECK constraints; missing player gives 404 `PLAYER_NOT_FOUND`. Not clear: every bean-validation failure returns the same generic body `{"code":"INVALID_INPUT","detail":"Invalid request shape, value, or method"}` with no field name or reason | LIVE (negative amount, missing field, unknown field, malformed UUID all returned the identical generic 400) |


## Findings (most severe first)

### F1. SERIOUS: commit-time wallet reconciliation trigger is O(n^2) in wallet history

- Location: src/main/resources/db/migration/V1__ledger.sql:120-158, function `check_wallet_ledger`, specifically the correlated subquery at lines 146-151.
- What it does: for every posting, the deferred trigger re-derives the running balance of EVERY entry the wallet has ever had (`for each entry e: sum of all entries with wallet_sequence <= e.wallet_sequence`). It fires twice per single-wallet posting (wallet UPDATE and player ledger_entry INSERT), four times per transfer.
- Evidence tag: BENCH, plus STATIC.
- Measured commit time of ONE credit posting (journal + 2 entries + wallet update), triggers active, session `statement_timeout = 15s`:

| Existing entries in wallet | COMMIT time |
| ---: | ---: |
| 100 | 6.4 ms |
| 1,000 | 71.7 ms |
| 5,000 | 1,511 ms |
| 10,000 | 6,067 ms |
| 20,000 | 24,106 ms |

- Growth ratio 10k to 20k = 3.97x, i.e. quadratic.
- Observed: the 24.1 s commit was NOT cancelled by the 15 s `statement_timeout`. This is consistent with PostgreSQL disarming the statement timer before commit processing, where deferred triggers run. Confidence in mechanism: high; confidence in observation: measured.
- Impact: the wallet row lock (`FOR UPDATE`) and the Hikari connection are held for the whole commit. Concurrent requests on that wallet wait, hit the 5 s `lock_timeout`, are retried 3 times by `CommandExecutor`, then fail with 503. With pool size 30, a few long-history wallets under load can drain the pool for all players. 20,000 lifetime transactions is roughly one year at 50 per day for an active player.
- README wording: "These strong database checks add write cost as a wallet's history grows; the load measurement reports the current implementation's cost." The load measurement uses at most 200 entries per wallet, so it does not expose this. Understated, not concealed.
- Recommendation: replace the full-history check with an incremental one: for the NEW entry, assert `balance_after = previous_entry.balance_after + amount` where previous is `wallet_sequence - 1` (O(1) via the existing `UNIQUE(wallet_id, wallet_sequence)` index), and assert `wallet.balance = NEW.balance_after` and `wallet.sequence = NEW.wallet_sequence` for the highest sequence in the transaction. Ship as a new versioned migration (V4) replacing the function body; keep the periodic full reconciliation for the admin endpoint. Add a test that bulk-loads a 5k-entry wallet and asserts a posting commits in bounded time.
- Reproduction: create postgres:17.6-alpine, apply docker/postgres/01-roles.sql and V1__ledger.sql, `SET session_replication_role = replica`, bulk insert N journal rows + N player entries (sequence i, balance_after i) + N issuance entries, set wallet balance/sequence to N, `SET session_replication_role = origin`, `ANALYZE`, then with `\timing on` run one BEGIN / insert journal / insert 2 entries / update wallet / COMMIT.

### F2. MODERATE: authorization on money endpoints has no HTTP-level tests

- Evidence tag: STATIC (grep of test sources), LIVE (probed manually).
- Endpoints exercised over HTTP in tests: `/v1/players`, `/v1/wallets/{id}/credits`, `/debits`, `/balance`, `/transactions` only.
- Endpoints with no HTTP test: `/v1/transfers`, `/v1/transactions/{id}/refunds`, `/v1/daily-login/claims`, `/v1/rewards/{id}/claims`, `/v1/promotions/{id}/claims`, `/internal/v1/action-completions`, `/v1/admin/reconciliation`.
- Consequence: role rules (`@PreAuthorize`) and sender-from-token logic on these endpoints are protected only by manual testing. A regression that let SERVICE call `/v1/transfers` or PLAYER call `/refunds` would pass the suite.
- Live probe results today: all correct (PLAYER refund 403, PLAYER completion 403, ADMIN completion 403, SERVICE transfer 403).
- Recommendation: add a MockMvc matrix test: each endpoint x each role -> expected status.

### F3. MODERATE: 24 of 35 error codes are never asserted by any test

- Evidence tag: STATIC.
- Asserted by name in tests (11): BALANCE_LIMIT, COMPLETION_NOT_OWNED, DAILY_ALREADY_CLAIMED, FORBIDDEN, IDEMPOTENCY_KEY_REUSED, INSUFFICIENT_FUNDS, INVALID_AMOUNT, INVALID_INPUT, REWARD_LIMIT_EXCEEDED, SEQUENCE_LIMIT, UNAUTHENTICATED.
- Never asserted by name (24): ALREADY_REFUNDED, BUSINESS_REFERENCE_USED, COMPLETION_NOT_FOUND, COMPLETION_REFERENCE_REUSED, COMPLETION_REWARD_MISMATCH, DEPENDENCY_UNAVAILABLE, INVALID_COMPLETION_REFERENCE, INVALID_PAGINATION, INVALID_PLAYER_IDENTITY, NOT_FOUND, PLAYER_EXISTS, PLAYER_NOT_FOUND, PLAYER_SUSPENDED, PROMOTION_ALREADY_CLAIMED, PROMOTION_DISABLED, PROMOTION_EXHAUSTED, PROMOTION_NOT_FOUND, RATE_LIMITED, REFUND_NOT_SUPPORTED, REWARD_ALREADY_CLAIMED, REWARD_DISABLED, REWARD_NOT_FOUND, SELF_TRANSFER, TRANSACTION_NOT_FOUND.
- Some of these are exercised by HTTP status only (e.g. promotion races count 409s). `PLAYER_NOT_FOUND` for credit/debit/balance has no test at all.
- Live probes today confirmed correct behaviour for PLAYER_NOT_FOUND, REFUND_NOT_SUPPORTED, TRANSACTION_NOT_FOUND, SELF_TRANSFER, BUSINESS_REFERENCE_USED, DAILY_ALREADY_CLAIMED.

### F4. MODERATE: every DataAccessException maps to 503 "retry with the same idempotency key"

- Location: src/main/java/com/example/walletledger/configuration/ApiProblems.java:30-38.
- Evidence tag: STATIC.
- A `DataIntegrityViolationException` (constraint violation) would be reported as a retryable dependency outage with `Retry-After: 1`. Today the advisory locks and pre-checks make this path effectively unreachable, so it is latent.
- Recommendation: map `DataIntegrityViolationException` to 409 and keep 503 for `TransientDataAccessException` / resource failures.

### F5. MINOR: promotion claims lock the campaign row before any eligibility pre-check

- Location: src/main/java/com/example/walletledger/rewards/application/RewardService.java:98-131; `RewardRepository.lockCampaign` :127.
- Evidence tag: STATIC.
- Already-claimed players and requests arriving after exhaustion still queue on the single `promotion` row lock. Under a viral promotion this converts into 5 s lock timeouts and 503s for requests that would be rejected anyway.
- Recommendation: unlocked read first; reject `PROMOTION_EXHAUSTED` / `PROMOTION_ALREADY_CLAIMED` early; take `FOR UPDATE` only for plausible winners. Correctness stays with the locked re-check.

### F6. MINOR: history rows omit refund origin and transfer counterparty

- Location: `WalletService.history` WalletService.java:216-262.
- Evidence tag: LIVE (row keys inspected: no `originalTransactionId` on REFUND rows, no counterparty on TRANSFER rows).
- The data exists in `journal_transaction.original_transaction_id` and the paired `ledger_entry`; the API just does not surface it. Reduces API-level auditability.

### F7. MINOR: inconsistent problem+json envelopes

- Evidence tag: LIVE.
- Rejections stored by `CommandExecutor.rejected` use `title = code` and omit `instance`. Framework-handled errors (`ApiProblems`) use `title = "Bad Request"/"Forbidden"` and include `instance`. The `code` field is stable in both paths, which is what clients should key on.

### F8. MINOR: unverifiable process claims

- Evidence tag: STATIC (git log).
- The repository has one squashed implementation commit, so the TDD narrative and "observed failures before implementation" in docs/build-evidence.md cannot be corroborated from history. The recorded numeric results themselves reproduced exactly.

### Additional observations, not graded as findings

- `Map<String, Object>` is the universal return type for receipts, balances and history; `RewardService.transactionId` parses a UUID back out of a map by `toString()`. No typed DTOs, no OpenAPI.
- `BusinessException` carries HTTP status codes and lives in `wallet.domain` but is shared by rewards, idempotency and configuration packages.
- `WalletService` (~490 lines) handles validation, locking, posting, outbox serialization, history and reconciliation; the rewards side has a repository split, the wallet side does not.
- Mixed SQL style: named parameters and lowercase keywords in `WalletService`, positional `?` and uppercase in `RewardRepository`.
- `player.status = 'SUSPENDED'` is checked on every lock but no code path or grant can set it; vestigial.
- Rate limiter fails open when Redis is down (documented, metric emitted). Acceptable since PostgreSQL is authoritative.
- Kafka consumer uses unlimited retry with 1 s backoff, so one poison record blocks its partition (documented as a limitation).
- Test `RewardRacesIT.fiveHundredPlayersCompete...` runs 500 virtual threads against a 30-connection pool with a 5 s Hikari connection timeout; it passed in 4.8 s here. On a slow CI host this could flake with `CannotGetJdbcConnectionException`. Low risk.


## Recommended remediation order

1. F1: replace `check_wallet_ledger` with an incremental O(1) check in a new migration; add a long-history performance test; re-run the benchmark in section 3 to prove the fix; update README trade-off text with numbers.
2. F2: HTTP authorization matrix test for all mutation endpoints and reconciliation.
3. F3: assert error codes by name for PLAYER_NOT_FOUND, SELF_TRANSFER, REFUND_NOT_SUPPORTED, ALREADY_REFUNDED, PLAYER_EXISTS, promotion codes, INVALID_PAGINATION.
4. F4: map `DataIntegrityViolationException` to 409.
5. F5: unlocked eligibility pre-check before locking the promotion row.
6. Invalid-input clarity: include field name and violation in 400 bodies (still one stable `code`).
7. F6: add `originalTransactionId` and transfer counterparty to history rows.
8. Design hygiene: typed receipt records, move `BusinessException` to a shared package, one SQL style.

## Side effects of this review on the local environment

- `target/` was rebuilt by my `clean verify`; the author's reports dated 2026-09-08 were replaced by mine dated 2026-09-09 with identical results.
- Compose demo database: Alice (`10000000-0000-0000-0000-000000000001`) moved from balance 215 / sequence 7 to 226 / sequence 9 via one 1-unit credit and one daily claim dated 2026-09-09; 10 `idempotency_request` rows with keys prefixed `claude-verify-` were created. Nothing existing was modified; the ledger is append-only. A rerun of scripts/demo.py today will get a tolerated 409 on the daily claim.
- No files in the repository were edited; `git status` is clean. The benchmark container was removed.
