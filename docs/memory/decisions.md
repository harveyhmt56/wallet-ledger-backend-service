# Decisions and requirements

Checked: 2026-09-09 against `682a1fd`; [baseline and retrieval rules](../../MEMORY.md).

## Confirmed product decisions

Preserved from the user's planning decisions in the [archived plan](archive/2026-09-07-plan.md), also retained by the [review plan](../review-remediation-plan.md#decision-and-scope):

- One in-game currency, whole-number units.
- A trusted server records completion; the client requests a claim. The server determines eligibility and amount.
- Full credit/debit reversal only; append inverse entries, preserve the original, allow at most one reversal per original, and reject an overdrawing reversal atomically. Partial refunds and transfer reversals are outside scope.

## Implemented design defaults

These are implementation choices, not additional separately confirmed product requirements.

| Choice | Source |
| --- | --- |
| `COIN`; Java `long` / PostgreSQL `BIGINT`; checked arithmetic, positive amounts, maximum player balance `Long.MAX_VALUE` | [MoneyRules](../../src/main/java/com/example/walletledger/wallet/domain/MoneyRules.java), [V1](../../src/main/resources/db/migration/V1__ledger.sql) |
| Server UTC date, one daily claim/date; consecutive days grant `10 × streak`, missed day resets; policy `daily-v1` | [DailyRewardPolicy](../../src/main/java/com/example/walletledger/rewards/domain/DailyRewardPolicy.java), [RewardService](../../src/main/java/com/example/walletledger/rewards/application/RewardService.java) |
| Refunds do not restore reward eligibility or promotion capacity | [WalletService.refund](../../src/main/java/com/example/walletledger/wallet/application/WalletService.java) |
| Mission: 100 units; promotion: 25 units for 100 distinct players; seeded definitions, no management API | [V2](../../src/main/resources/db/migration/V2__rewards.sql), [RewardController](../../src/main/java/com/example/walletledger/rewards/api/RewardController.java) |
| Project-lifetime retention of ledger, idempotency and consumer deduplication; archival is future work | [README limitations](../../README.md#assumptions--limitations) |
| Single database owns all wallets; synchronous posting; Redis fail-open rate limits; Kafka at-least-once notifications | [Implementation map](implementation.md) |

## Invariants to preserve

These are required properties; the [known SQL integrity gap](verification.md#pending-remediation) limits claims about arbitrary runtime-role SQL today.

- Player balances stay non-negative and equal their ledger sums; sequences advance without gaps, and entry running balances match.
- Each journal has exactly two distinct accounts, equal opposite amounts, one currency, and a zero sum. Platform issuance/purchase totals are derived; no shared mutable platform wallet.
- Every balance change appends explanatory history and one outbox event per affected player, atomically with wallet and entitlement state.
- Business uniqueness survives changing request keys: one claim/completion, one daily claim/player/date, one promotion claim/player/campaign, one full reversal/original.
- Business rejection leaves no partial money or entitlement writes, while its completed idempotency response can persist. Infrastructure failure rolls back the whole command.

## Assignment and working expectations

- Demonstrate money correctness, understandable design, concurrency behavior, tests and honest limitations.
- Required stack: Java 21, Spring Boot **3.5.16**, PostgreSQL, Redis, Docker Compose, Kafka and Flyway. Do not silently upgrade the required Boot version; the historical lifecycle reference is preserved in the archive.
- Required wallet functions: credit/debit, insufficient-funds rejection, current balance, paginated history, permanent reason/reference records, idempotency, concurrency safety, atomic failures and clear input errors. Supporting scope includes the implemented rewards, transfers, refunds and events.
- Preserve README's five sections: run/setup/database/tests; design decisions/trade-offs; concurrency/idempotency; testing approach; assumptions/limitations.
- For behavior changes, retain executable acceptance examples, focused red-green-refactor and mutation checks, real infrastructure tests, and small reviewable changes. Do not reconstruct or invent historical TDD commits.
- One database and a modular service remain intentional. Sharded transfers, microservices, extra currencies and partial refunds are outside the current design.
