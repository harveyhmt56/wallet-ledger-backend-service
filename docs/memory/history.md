# History (compacted)

Dated, superseded facts; nothing here needs re-running. Current facts live in [state](state.md). Append one row per meaningful change; keep rows to one line and put counts in the linked document, not here.

## Timeline

| Date | Commit | What happened | Evidence |
| --- | --- | --- | --- |
| 2026-09-07 | — | Planning session; user confirmed the three product decisions (now in [requirements](requirements.md)). Original plan text: `git show f2a7e07:docs/memory/archive/2026-09-07-plan.md` | — |
| 2026-09-08 | `f4e6b1a` | Implementation in one squashed commit: ledger, rewards, transfers, refunds, outbox/projection, Compose demo | [build evidence](../build-evidence.md) |
| 2026-09-09 | `77ac43c` | Independent review pass 1 at `4a1a954`: F1 serious quadratic deferred trigger (24 s commit at 20k entries), F2–F8 moderate/minor | [review](../review-by-harvey-with-claude.md) |
| 2026-09-09 | `682a1fd` | Fact-checked remediation plan: findings R1–R6, ordered steps 1–6, acceptance criteria | [plan](../review-remediation-plan.md) |
| 2026-09-09 | `79032c9` | GitHub Actions `verify.yml` added; passes locally; hosted run never confirmed | [CI evidence](../build-evidence.md#github-actions-verification) |
| 2026-09-09 | `9ef2639` | **Step 1, V4** indexed ledger integrity; closed the quadratic commit (V3 24.9 s → V4 ~1.7 ms at 20k entries) and TEMP-shadow bypass; `transaction_timeout` bounds deferred COMMIT, `statement_timeout` does not | [V4 evidence](../ledger-integrity-v4.md) |
| 2026-09-10 | `2fe9ce4` | Review pass 2: F1 closed; V4 audit SELECT run read-only against the live Compose demo DB (zero findings; demo DB left on V3); notes N1–N4 | [review](../review-by-harvey-with-claude.md) |
| 2026-09-10 | `45b28ab` | Second-pass fact check corrected readiness verdict, HTTP coverage wording, retry count, promotion lock ordering, audit-versus-commit semantics | [second pass](../review-remediation-plan.md#second-pass-fact-check--2026-09-10) |
| 2026-09-10 | `760f4b0` | **Step 2** transfer receipt privacy, HTTP authorization matrix, signed-JWT tests, required issuer/audiences | [step 2 evidence](../api-security-step2.md) |
| 2026-09-11 | `8232ee7` | QA audit at `760f4b0` (docs only): black-box money races, refunds, 429/fail-open, SIGKILL recovery, projection convergence on an isolated stack; findings F-01–F-11 | [QA audit](../qa-audit-2026-09-11.md) |
| 2026-09-13 | `1d19764` | Both reviews fact-checked; F-01/F-03 mechanics confirmed; overstated coverage claims corrected; application unchanged | [summary](../evidence/review-fact-check-2026-09-13/summary.json) |
| 2026-09-13 | `e6472f7` | **Test-gap closure F-04–F-08**, tests and `pom.xml` only; PIT gate widened to three adapter classes | [test-gap evidence](../test-gap-evidence-2026-09-13.md) |
| 2026-09-13 | `f2a7e07` | Memory split into requirements / state / implementation / verification / history (`orchestrate/memory-mgt-v2`; the unmerged `-v1` from `760f4b0` is superseded) | — |
| 2026-09-14 | `6ba729d` | Full money QA at `e6472f7`: four regression cases added; manual money/adapter mutations detected; F-01/F-02/F-03 reproduced; outage recovery safe; no application changes | [audit](../qa-audit-2026-09-14.md), [counts](../evidence/qa-2026-09-14/final-gate-summary.json) |
| 2026-09-14 | `5ffb0d2` | **Step 3 first slice, F-01**: cause-aware transaction-start 503 and safe unexpected-transaction 500 in `ApiProblems`; real stopped-DB recovery test | [F-01 evidence](../database-outage-f01.md) |
| 2026-09-15 | `orchestrate/memory-mgt-v3` | Memory re-baselined on `5ffb0d2`; superseded gate numbers and the archived plan removed from notes | — |
| 2026-09-15 | `9b9ccd9` + working tree | **Step 4 recovery slice, F-02**: V5 durable Kafka quarantine, bounded retry, offset/commit/restart regressions, audited authoritative replay; full gate and expanded PIT pass | [F-02 evidence](../kafka-quarantine-f02.md) |
| 2026-09-16 | `863d63f` + working tree | **Step 4 validation slice, F-03**: exact event integer/long checks before writes; parser and real listener quarantine/recovery regressions; full gate and expanded PIT pass; F-02 confirmed committed at baseline | [F-03 evidence](../balance-projection-f03.md) |

## Closed findings

- **F1 / R1 quadratic trigger** and **R2 TEMP-table shadowing**: closed by V4 (`9ef2639`), verified 2026-09-10. TEMP privilege intentionally retained on `wallet_app`.
- **R3 / F2 recipient-balance disclosure and untested HTTP authorization**: closed by step 2 (`760f4b0`), verified 2026-09-10.
- **F-04–F-08 test-quality findings** (refund-lock blind spot, debit reversal never executed, missing player untested, status-only race assertions, production paths never run under `verify`): closed by `e6472f7`, verified 2026-09-13.
- **F-01 stopped-database HTTP error contract**: closed by `5ffb0d2`, verified 2026-09-14; classification limits in [F-01 evidence](../database-outage-f01.md).
- **F-02 poison Kafka record stall**: verified 2026-09-15, committed at `863d63f`; durable quarantine, three-attempt retry and audited replay. [Evidence and limits](../kafka-quarantine-f02.md).
- **F-03 oversized/coerced event integers**: implemented and verified 2026-09-16 in the `coder/corrupt-projection` working tree; exact types/ranges before writes, durable quarantine and valid same-ID recovery. [Evidence and limits](../balance-projection-f03.md).

## Superseded evidence

Gates through F-02 (`863d63f`) are superseded by the F-03 working-tree gate in [state](state.md); linked documents above retain their counts. The last JaCoCo (97.2% lines / 100% branches) and broader all-class PIT (94.5%) measurements were taken at `e6472f7`/`6ba729d` and are not part of the pom gate ([2026-09-14 audit](../qa-audit-2026-09-14.md)). Local load figures exclude HTTP, Redis and Kafka and set no capacity target ([build evidence](../build-evidence.md#local-load-measurement)). Raw logs under `/private/tmp` and `target/` reports are ephemeral.

## Environment notes worth keeping

- Local gates ran on JDK 21.0.8, ARM64 macOS, OrbStack Docker, Maven 3.9.11, PIT 1.30.0, JaCoCo 0.8.13.
- `gh` API returned 404 from this machine; a GitHub-hosted CI run has never been confirmed.
- Commits on `main` may be tree-identical to their source branches (`5ffb0d2` = `coder/recovery-db-outage` `e552a24`; `6ba729d` = `qa/QA-full-project-verify` `4acb084`; `8232ee7` = `1b04d5e`); evidence files may cite the branch hash.
