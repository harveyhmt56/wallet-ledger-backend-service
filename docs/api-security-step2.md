# Transfer privacy and API authorization — step 2

Verified locally on 2026-09-10 against baseline `45b28ab` plus this remediation, on Java 21.0.8 and Spring Boot 3.5.16. The [remediation plan](review-remediation-plan.md#step-2-close-transfer-disclosure-and-prove-api-authorization) defines the scope. This closes a directly reproducible player-balance disclosure; Kafka durability remains a separate high-priority production gate.

## Delivered behavior

`POST /v1/transfers` now projects an explicit sender view **after** `CommandExecutor` executes or replays a command. [TransferReceipt](../src/main/java/com/example/walletledger/wallet/api/TransferReceipt.java) allows only `transactionId`, `operation`, `amount`, `reason`, `occurredAt`, `playerId`, `walletId`, `balanceAfter`, `walletSequence` and `recipientId`. The sender's balance remains the original transfer result. The recipient's balance and unlisted internal fields are absent on fresh results, ordinary replay and replay of historical stored receipts.

This is an intentional response-contract removal of `recipientBalanceAfter`. The projection copies JSON before filtering; stored receipts, fingerprints, journal identities and immutable history are not rewritten. [WalletController](../src/main/java/com/example/walletledger/wallet/api/WalletController.java) preserves command status and problem bodies. No money posting, transaction, locking, migration or idempotency implementation changed.

Nonlocal startup now requires `spring.security.oauth2.resourceserver.jwt.issuer-uri` to have text and `spring.security.oauth2.resourceserver.jwt.audiences` to contain at least one member, all with text. [RequiredJwtConfiguration](../src/main/java/com/example/walletledger/configuration/RequiredJwtConfiguration.java) rejects incomplete settings before the nonlocal filter chain is built. Boot still supplies the decoder; this does not replace its issuer, audience, signature or timestamp validation. The `local` demonstration profile remains available without JWT settings. See [README configuration](../README.md#how-to-run-setup-database-and-tests).

## Acceptance evidence

| Test | What it proves |
| --- | --- |
| [TransferReceiptTest](../src/test/java/com/example/walletledger/wallet/TransferReceiptTest.java), 3 cases | Exact public fields, removal of recipient funds and an unknown nested private field, stored JSON unchanged, 400/409 status and problem-body preservation through the controller mapping. |
| [TransferPrivacyIT](../src/test/java/com/example/walletledger/wallet/TransferPrivacyIT.java), 3 cases | Real PostgreSQL and MockMvc: fresh and historical receipts are safe, replay keeps transaction/time/sender snapshot after later postings, stored receipt text is unchanged, balances/sequences/entry counts show no reposting. Insufficient-funds replay and key-content conflicts keep their codes. |
| [HttpAuthorizationIT](../src/test/java/com/example/walletledger/wallet/HttpAuthorizationIT.java), 81 cases | Twelve real controller operations × six caller categories (72 cases), plus nine injection/entitlement cases. Complete affected-row snapshots cover wallet, journal, entries, outbox, claims, completions, promotion capacity and idempotency state. |
| [JwtConfigurationTest](../src/test/java/com/example/walletledger/configuration/JwtConfigurationTest.java), 9 cases | Missing/empty/whitespace issuer or audience and a blank audience-list member fail startup; valid nonlocal and local profiles start. The isolated configuration harness supplies a fake decoder. |
| [ConfiguredJwtHttpIT](../src/test/java/com/example/walletledger/configuration/ConfiguredJwtHttpIT.java), 12 cases | Real random-port HTTP, actual controllers, Boot's configured decoder and PostgreSQL. A loopback JWK fixture supplies a generated RSA public key. Valid signed tokens work; wrong/missing issuer or audience, expired/future tokens and invalid signatures return 401. Signed roleless/SERVICE/ADMIN tokens cannot transfer, and local Basic credentials cannot authenticate outside `local`. Invalid-token requests reserve no key; the same command/key then succeeds with a valid token. |

The role matrix covers provision, credit, debit, balance, history, transfer, refund, daily claim, trusted reward claim, promotion claim, completion recording and reconciliation. Caller categories are anonymous, roleless, owning PLAYER, other PLAYER, SERVICE and ADMIN. Both PLAYER identities may perform self-scoped actions; the authenticated subject chooses whose funds or entitlement change. ADMIN does not imply SERVICE or PLAYER privileges.

Route authentication/authorization and strict-body validation denials occur before command reservation and leave checked state unchanged. A **business** rejection such as `COMPLETION_NOT_OWNED` deliberately persists its idempotent rejection while leaving money and entitlements unchanged. These are separate boundaries. Bodyless daily/promotion claim routes ignore supplied JSON; tests prove that injected identity/amount cannot override the authenticated player or server policy. Trusted-reward bodies reject unknown fields, and players cannot record trusted completions.

## Observed red and green

Before production edits, `TransferReceiptTest` failed because recipient funds and an unknown private field were returned. Real-PostgreSQL `TransferPrivacyIT` then failed its fresh and historical receipt assertions: 2 expected failures out of 3 cases, zero errors/skips. The rejection-contract control passed.

Before adding the JWT guard, the corrected configuration harness reported 7 expected failures out of 9: incomplete issuer/audience settings allowed startup. Valid/local controls passed. Earlier test compilation/MVC harness failures were corrected and are not counted as red evidence.

Focused green verification passed 13 unit/adapter and 96 integration cases, with zero failures/errors/skips. Final completion command:

```sh
# Run with JAVA_HOME pointing to a Java 21 installation; Docker is required.
./mvnw --batch-mode --no-transfer-progress clean verify -Pmutation
```

Fresh final XML/report inspection:

- **27 unit/adapter + 186 integration cases**, zero failures, errors or skips.
- Packaging and Spotless passed; Java 21 and Spring Boot 3.5.16 retained.
- PIT: **26/26 KILLED (100%)**, zero survived/uncovered/timed-out/errored or other outcomes. The new transfer projection contributed 4 killed mutants and required JWT settings contributed 3; the existing four domain classes contributed 19. Mutated-class line coverage was 42/47 (89%), above the unchanged 80% gates.
- The integration total includes the existing **12/12 targeted real-PostgreSQL SQL mutation cases**. Existing concurrency, atomic rollback, uncertain-commit replay, V4 migration, long-history, Redis and Kafka regressions passed in the same gate.

The command log is `/private/tmp/step2-full-verify.log`; focused/red logs use `/private/tmp/step2-*.log`. Generated XML and PIT HTML/XML are under `target/surefire-reports`, `target/failsafe-reports` and `target/pit-reports`. These local artifacts are ephemeral; this document records the inspected results rather than claiming immutable report provenance.

## Evidence limits and primary references

The endpoint matrix and receipt HTTP tests use Spring-supplied identities with real controllers and PostgreSQL; they do not decode tokens. The separate signed-token suite exercises real network HTTP and Boot decoding against a local fixture. No production identity provider, hosted CI run, deployed database, Kafka replication topology or deployment was exercised. PIT targets the four domain classes and two new policy/projection classes, not every controller/filter or service; passing mutants are focused guard evidence, not exhaustive security proof.

- [OWASP API3 prevention guidance](https://github.com/OWASP/API-Security/blob/master/editions/2023/en/0xa3-broken-object-property-level-authorization.md) recommends explicitly selecting authorized response properties; the sender allowlist follows that guidance.
- [Spring Security 6.5 JWT configuration](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html) documents issuer/audience checks, signature/timestamp validation and issuer plus JWK-set configuration. The tests use timestamps well outside the default clock skew. Boot 3.5.16's installed decoder auto-configuration was also checked for issuer/audience validator wiring.
- [Spring Security MockMvc authentication](https://docs.spring.io/spring-security/reference/6.5/servlet/test/mockmvc/authentication.html) defines the supplied-identity boundary used by the role matrix.

Steps 3–6 remain subject to the [pending remediation and release gates](memory/verification.md#pending-remediation), including error classification, history audit fields, messaging durability/recovery, promotion contention and controlled production adoption of V4.
