# History (compacted)

Dated, superseded facts. Each row links to its canonical document; nothing here needs re-running. Current facts live in [state](state.md). Append one row per meaningful change; do not add narrative to active notes.

## Timeline

| Date | Commit | What happened | Evidence |
| --- | --- | --- | --- |
| 2026-09-07 | — | Planning session; user confirmed the three product decisions and said "plan first, do not build yet" | [archived plan](archive/2026-09-07-plan.md) |
| 2026-09-08 | `f4e6b1a` | Implementation in one squashed commit: ledger, rewards, transfers, refunds, outbox/projection, Compose demo. 15 unit + 32 IT green, PIT 19/19 over four domain classes. Demo ended Alice 215/7, Bob 20/1, 8 events consumed | [build evidence](../build-evidence.md) |
| 2026-09-09 | `4a1a954` reviewed, `77ac43c` | Independent review pass 1. F1 SERIOUS: deferred trigger `check_wallet_ledger` scanned history quadratically; one credit COMMIT took 24.1 s at 20k entries and `statement_timeout` could not cancel it. F2–F8 moderate/minor | [review](../review-by-harvey-with-claude.md) |
| 2026-09-09 | `682a1fd` | Fact-checked remediation plan: findings R1–R6, ordered steps 1–6, acceptance criteria; reproduced 15+32 and 19/19 | [plan](../review-remediation-plan.md) |
| 2026-09-09 | `14b1e8f` | Memory restructured into index, notes and archive | — |
| 2026-09-09 | `79032c9` | GitHub Actions `verify.yml` added; the CI command passed locally; actionlint clean; hosted run never confirmed | [CI evidence](../build-evidence.md#github-actions-verification) |
| 2026-09-09 | `9ef2639` | **Step 1, V4** indexed ledger integrity. Red: four TEMP-shadow bypasses committed, corrupt V3 fixtures upgraded silently, 25.7 s credit at 20k. Green: 15 unit + 90 IT, PIT 19/19, 12/12 SQL mutants. Credit at 1k/5k/10k/20k entries: V3 75/1520/6004/24930 ms, V4 1.67/1.57/1.37/1.69 ms. Deadline tests: `statement_timeout` does not bound deferred COMMIT, PostgreSQL 17 `transaction_timeout` does | [V4 evidence](../ledger-integrity-v4.md), [raw JSON](../evidence/) |
| 2026-09-10 | `2fe9ce4` / `b252606` | Review pass 2 at `9ef2639`: F1 closed. Gate re-run 15+90, 19/19, credit ~1.5–1.9 ms flat. V4 audit SELECT run read-only against the live Compose demo DB (still V3, Alice 226/9): zero findings; demo DB not migrated. Notes N1–N4 added | [review](../review-by-harvey-with-claude.md) |
| 2026-09-10 | `9373e18` / `45b28ab` | Second-pass fact check: corrected readiness verdict, HTTP coverage wording, blanket 409 proposal, retry count (three attempts total), promotion lock ordering, inherited TEMP privilege, audit-versus-commit semantics. 35 error codes / 11 asserted / 24 never asserted confirmed statically | [second pass](../review-remediation-plan.md#second-pass-fact-check--2026-09-10) |
| 2026-09-10 | `760f4b0` | **Step 2** transfer receipt privacy and authorization. Red: recipient funds escaped in one unit and two HTTP cases; seven invalid JWT configurations started. Green: 27 unit + 186 IT, PIT 26/26 (incl. 4 projection + 3 JWT mutants), 12/12 SQL; `HttpAuthorizationIT` 72 endpoint × caller + 9 input cases; `ConfiguredJwtHttpIT` 12 cases | [step 2 evidence](../api-security-step2.md) |
| 2026-09-11 | `8232ee7` (same content as `1b04d5e`) | QA audit at `760f4b0`, documentation only: JaCoCo 90.7% / 68.3%, whole-service PIT 233/307 (76%), black-box 176/179 on an isolated Compose stack (money races, refunds incl. debit reversal, 429 and fail-open, SIGKILL recovery, projection convergence); findings F-01–F-11 | [QA audit](../qa-audit-2026-09-11.md) |
| 2026-09-13 | `1d19764` | Both reviews fact-checked at the audit baseline: fresh scratch gate 27 + 186, PIT 26/26, 46.4 s; eight advice/driver checks and Jackson overflow probes confirmed F-01/F-03 mechanics; stale authorization/privacy status and overstated debit-reversal/404 coverage claims corrected; application unchanged | [summary](../evidence/review-fact-check-2026-09-13/summary.json) |
| 2026-09-13 | `e6472f7` | **Test-gap closure F-04–F-08** and unit expansion, tests and `pom.xml` only. 148 cases added → 97 unit + 264 IT; normal PIT gate 26/26 → 52/52 with three adapter classes added; combined JaCoCo 90.7/68.3 → 97.2/100; broader PIT 76.0 → 94.5%; 15 manual contract mutations + 3 JWT role deletions detected; restored controls pass. Reward rejection snapshot helper: 295 ms / 11 MB → 5.8 ms / 561 B at 20k postings | [test-gap evidence](../test-gap-evidence-2026-09-13.md), [counts](../evidence/test-gaps-2026-09-13/summary.json) |
| 2026-09-13 | `orchestrate/memory-mgt-v2` | Memory compacted into requirements / state / implementation / verification / history on top of `e6472f7`. The earlier `orchestrate/memory-mgt-v1` restructure (from `760f4b0`) was never merged and is superseded | — |

## Closed findings

- **F1 / R1 quadratic trigger.** Closed by V4, verified 2026-09-10.
- **R2 TEMP-table shadowing.** Closed by V4 (qualified names, pinned `search_path`); TEMP privilege intentionally retained on `wallet_app`.
- **R3 / F2 recipient-balance disclosure and untested HTTP authorization.** Closed by step 2, verified 2026-09-10.
- **F-04–F-08 test-quality findings.** Closed by `e6472f7`, verified 2026-09-13: refund/provision races assert exact loser codes and fail without their advisory locks; debit reversal, missing/suspended player and duplicate-reference paths run in automation; first-delivery/zero projection asserted; advice, filter, relay and real listener run under `verify`.

## Superseded evidence (do not re-run)

- Pre-V4 gates: 15 unit/adapter + 32 IT, PIT 19/19 (`f4e6b1a`, `682a1fd`, `79032c9`); V4 gate 15 + 90, 19/19, 12/12 SQL mutants (`9ef2639`, `2fe9ce4`); step 2 gate 27 + 186, 26/26 (`760f4b0`, `1d19764`). All superseded by the `e6472f7` gate in [state](state.md#latest-evidence-fresh-2026-09-13-at-e6472f7).
- Historical local load figures exclude HTTP, Redis and Kafka relay, use short histories and set no capacity target: [build evidence](../build-evidence.md#local-load-measurement).
- Full logs under `/private/tmp` (`step2-full-verify.log`, `ledger-v4-final-verify.log`, `wallet-test-gaps-*`) and generated `target/` reports are ephemeral; committed reports preserve observations, not the raw artifacts.

## Environment notes worth keeping

- Local gates ran on JDK 21.0.8 (GraalVM for the fact-check), ARM64 macOS, OrbStack Docker, Maven 3.9.11, PIT 1.30.0, JaCoCo 0.8.13.
- Both review passes name `4a1a954a844e951e74531303e855574551d6e14f`; `git diff 4a1a954 682a1fd` changed only memory and review docs, so the reviews are not evidence that remediation was implemented.
- The Docker build once failed because the JDK image lacked `unzip`; the wrapper now extracts the ZIP whose SHA-256 starts `0d7125e8`.
- `gh` API returned 404 from this machine; a GitHub-hosted CI run has never been confirmed.
