# Goals, requirements and confirmed decisions

Durable note: rewrite only when the user changes the requirement. Checked 2026-09-17 against `a3b7f9d` (requirements unchanged since 2026-09-09); [index](../../MEMORY.md).

## Project goal

A production-grade wallet ledger backend for one whole-unit in-game currency. The wallet balance must always be right, no matter how many requests come in, in what order, or what breaks along the way. The assignment brief (supplied by the user on 2026-09-09; not stored elsewhere) grades money-moving correctness, service design, concurrency and edge cases, test quality and documentation.

## Must-fulfil requirements

| Area | Requirement |
| --- | --- |
| Core wallet | Credit; debit; reject an insufficient-balance debit; current balance; paginated transaction history |
| Permanent record | Every balance change leaves a permanent record of what changed and why (reward, purchase, admin action, reference) |
| Safety | Idempotent requests; no incorrect state under concurrent requests; no partial updates on failure; clear handling of invalid input (negative amounts, missing player) |
| Tests | Must protect money paths under pressure: concurrent requests and repeated submissions |
| README | Five sections: run/setup/database/tests; design decisions, ledger approach and trade-offs; concurrency & idempotency; testing approach (especially concurrent debit); assumptions & limitations |
| Stack | Java 21, Spring Boot **3.5.16** (never upgrade silently), PostgreSQL, Redis, Kafka, Flyway, Docker Compose |

Supporting features, all implemented: daily login streak with an increasing reward that resets after a missed day; player-to-player transfer; refund/reversal of a cancelled purchase or reward; reward claim where the server decides eligibility; promotional reward limited to the first N players; domain events on every balance change.

## Confirmed product decisions (user, 2026-09-07)

1. **Currency:** one in-game currency, whole-number units.
2. **Reward evidence:** a trusted server records completion; the client requests a claim; the server decides eligibility and amount.
3. **Refunds:** full reversal of credits and debits only. Append inverse entries, keep the original, at most one reversal per original, reject an overdrawing reversal atomically. Partial refunds and transfer reversals are out of scope.

No product clarification is pending.

## Implemented design defaults (choices, not extra requirements)

| Choice | Source |
| --- | --- |
| `COIN`; Java `long` / PostgreSQL `BIGINT`; checked arithmetic; positive amounts; balance cap `Long.MAX_VALUE` | [MoneyRules](../../src/main/java/com/example/walletledger/wallet/domain/MoneyRules.java), [V1](../../src/main/resources/db/migration/V1__ledger.sql) |
| UTC server date, one daily claim per date; consecutive days grant `10 × streak`; a missed day resets; policy `daily-v1` | [DailyRewardPolicy](../../src/main/java/com/example/walletledger/rewards/domain/DailyRewardPolicy.java) |
| Refunds do not restore reward eligibility or promotion capacity | [WalletService.refund](../../src/main/java/com/example/walletledger/wallet/application/WalletService.java) |
| Mission 100 units; promotion 25 units for 100 distinct players; seeded definitions, no management API | [V2](../../src/main/resources/db/migration/V2__rewards.sql) |
| Ledger, idempotency and consumer-dedup rows are kept for the project lifetime; archival is future work | [README limitations](../../README.md#assumptions--limitations) |
| One database owns all wallets; synchronous posting; Redis fail-open rate limits; Kafka at-least-once notifications | [implementation](implementation.md) |

Deliberately outside the current design: sharded transfers, microservices, extra currencies, partial refunds.

## Invariants to preserve

- Player balances stay non-negative and equal their ledger sums; wallet sequences advance without gaps; entry running balances match.
- Each journal has exactly two distinct accounts, equal opposite amounts, one currency and a zero sum. Platform issuance/purchase totals are derived; there is no shared mutable platform wallet.
- Every balance change appends explanatory history and one outbox event per affected player, atomically with wallet and entitlement state.
- Business uniqueness survives changing request keys: one claim per completion, one daily claim per player per date, one promotion claim per player per campaign, one full reversal per original.
- A business rejection leaves no partial money or entitlement writes (its rejection response may persist); an infrastructure failure rolls back the whole command.
- Known limit: generic commit triggers do not enforce per-operation sign/account semantics, and the inverse-refund shape is audit-checked, not commit-enforced. See [open findings](state.md#open-findings).

## Working expectations

- Keep explanations concise; cross-check technical claims against committed code, tests and primary documentation.
- Behavior changes: executable acceptance examples, red-green-refactor, real PostgreSQL/Redis/Kafka tests, focused mutation checks, small reviewable changes. Add a new migration for any change to an applied schema. Never reconstruct or invent historical TDD commits.
