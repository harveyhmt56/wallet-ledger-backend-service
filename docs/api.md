# API reference

[Quick start](../README.md#run-locally) · [Development setup](development.md)

Base URL for the local stack: `http://localhost:8080`.

## Authentication

The `local` profile uses HTTP Basic authentication with fixed demo credentials:

| Username | Password | Access |
| --- | --- | --- |
| `service` | `service-password` | Provision, credit/debit, full refunds, completion evidence and wallet reads |
| `admin` | `admin-password` | Provision, credit/debit, full refunds, wallet reads, reconciliation and metrics |
| `10000000-0000-0000-0000-000000000001` | `alice-password` | Alice's reads, transfers and reward claims |
| `10000000-0000-0000-0000-000000000002` | `bob-password` | Bob's reads, transfers and reward claims |

Authentication does not create a wallet. Run the [demo](../scripts/demo.py) or call `POST /v1/players` to provision it.

Outside `local`, configure OAuth2 JWT authentication:

- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`: required, nonblank issuer.
- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES`: required accepted audience(s), with no blank members.
- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`: optional explicit JWK endpoint to avoid issuer discovery.

Signed `roles` claims contain `PLAYER`, `SERVICE` or `ADMIN`; a player's `sub` is their provisioned UUID. Startup rejects missing issuer/audience configuration. Tests use signed tokens and a local JWK fixture; integration with a production identity provider remains deployment-specific. See [Spring Security JWT configuration](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html) and the [security test map](memory/verification.md#test-navigation).

## Write a credit

After provisioning Alice, run:

```sh
curl --fail-with-body -u service:service-password \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: example-credit-1' \
  -d '{"amount":100,"reason":"Mission completed","source":"mission-server","reference":"mission-42"}' \
  http://localhost:8080/v1/wallets/10000000-0000-0000-0000-000000000001/credits
```

Every write requires a nonblank `Idempotency-Key` of at most 200 characters. Reuse it when retrying the same action. References must be unique within `(operation, source, reference)` across all wallets.

A **monetary body** contains a positive integer `amount`, nonblank `reason` (max 500 characters), `source` (max 100) and `reference` (max 200). Fractions, numeric strings, zero, negative/overflowing amounts and unknown JSON properties are rejected.

## Endpoints

`SERVICE` and `ADMIN` can read any wallet; `PLAYER` can read only their own. Player actions derive the acting player from authentication.

| Method and path | Caller | Body / result |
| --- | --- | --- |
| `POST /v1/players` | SERVICE / ADMIN | `playerId`; creates a zero-balance wallet |
| `POST /v1/wallets/{playerId}/credits` | SERVICE / ADMIN | Monetary body; receipt |
| `POST /v1/wallets/{playerId}/debits` | SERVICE / ADMIN | Monetary body; receipt or insufficient-funds rejection |
| `GET /v1/wallets/{playerId}/balance` | Authorized reader | Current PostgreSQL balance and sequence |
| `GET /v1/wallets/{playerId}/transactions?limit=20&cursor=42` | Authorized reader | `items`, `nextCursor`; descending sequence, exclusive cursor; limit 1–100 |
| `POST /v1/transfers` | PLAYER | Monetary body plus `recipientId`; sender receipt |
| `POST /v1/transactions/{transactionId}/refunds` | SERVICE / ADMIN | `reason`, `source`, `reference`; full credit/debit reversal |
| `POST /v1/daily-login/claims` | PLAYER | No business payload; UTC daily reward |
| `POST /internal/v1/action-completions` | SERVICE | `playerId`, `rewardId`, `source`, `reference`; returns completion evidence |
| `POST /v1/rewards/{rewardId}/claims` | PLAYER | `completionReference` from the completion endpoint |
| `POST /v1/promotions/{promotionId}/claims` | PLAYER | No business payload; limited reward |
| `GET /v1/admin/reconciliation` | ADMIN | Balance/ledger comparison |

Seeded mission: `20000000-0000-0000-0000-000000000001`, 100 units, policy `mission-v1`. Seeded promotion: `30000000-0000-0000-0000-000000000001`, 25 units each for 100 distinct players, policy `promotion-v1`. Consecutive daily logins award `10 × streak day`; a missed day resets the streak. Use a new idempotency key for each day's claim.

## Responses and retries

Successful mutations return HTTP 200 receipts. Replay retains the original `balanceAfter`; use `/balance` for current funds. Transfer responses expose the sender's balance/sequence and recipient identity, but never the recipient's balance, including legacy stored replays.

Handled API errors use `application/problem+json` with a stable `code`:

| Status | Meaning |
| --- | --- |
| 400 | Invalid input |
| 401 / 403 | Authentication / authorization failure |
| 404 | Missing resource |
| 409 | Business conflict, including insufficient funds or key reuse with different content |
| 429 | Request quota exceeded |
| 503 | Recognized transaction-start connection failure, or a handled `DataAccessException`; includes `Retry-After: 1` |
| 500 | Sanitized unexpected transaction failure |

Business rejections inside command execution are stored for replay. Validation and authorization failures before execution are not stored. After an uncertain outcome, preserve the original idempotency key when retrying.

Known gaps: every handled `DataAccessException` currently receives 503 retry guidance even when nontransient; some reads return 500 during a database outage, and error details are not fully consistent across framework and business failures. See [open findings](memory/state.md#open-findings).

The default quota is 120 authenticated requests per 60-second window. Redis failures fail open and increment `wallet.rate_limit.degraded`; money checks remain in PostgreSQL. Health endpoints are public; other exposed Actuator endpoints require ADMIN. Responses include a generated `X-Correlation-ID` for matching logs.
