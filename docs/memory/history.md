# History (compacted)

Dated, superseded facts; nothing here needs re-running. Current facts live in [state](state.md). Append one row per meaningful change; keep rows to one line and put counts in the linked document, not here.

## Timeline

| Date | Commit | What happened | Evidence |
| --- | --- | --- | --- |
| 2026-09-07 | — | Planning session; user confirmed the three product decisions (now in [requirements](requirements.md)). Original plan text: `git show f2a7e07:docs/memory/archive/2026-09-07-plan.md` | — |
| 2026-09-08 | `f4e6b1a` | Implementation in one squashed commit: ledger, rewards, transfers, refunds, outbox/projection, Compose demo | [build evidence](../build-evidence.md) |
| 2026-09-09 | `77ac43c` · `682a1fd` · `79032c9` | Review pass 1 at `4a1a954` (F1 serious quadratic deferred trigger, F2–F8); fact-checked remediation plan R1–R6 with steps 1–6; GitHub Actions `verify.yml` (passes locally, hosted run never confirmed) | [review](../review-by-harvey-with-claude.md), [plan](../review-remediation-plan.md), [CI](../build-evidence.md#github-actions-verification) |
| 2026-09-09 | `9ef2639` | **Step 1, V4** indexed ledger integrity; closed the quadratic commit (V3 24.9 s → V4 ~1.7 ms at 20k entries) and the TEMP-shadow bypass; `transaction_timeout` bounds deferred COMMIT, `statement_timeout` does not | [V4 evidence](../ledger-integrity-v4.md) |
| 2026-09-10 | `2fe9ce4` · `45b28ab` | Review pass 2 (F1 closed, notes N1–N4) and second-pass fact check correcting readiness verdict, HTTP coverage wording, retry count, promotion lock ordering, audit-versus-commit semantics | [review](../review-by-harvey-with-claude.md), [second pass](../review-remediation-plan.md#second-pass-fact-check--2026-09-10) |
| 2026-09-10 | `760f4b0` | **Step 2** transfer receipt privacy, HTTP authorization matrix, signed-JWT tests, required issuer/audiences | [step 2 evidence](../api-security-step2.md) |
| 2026-09-11 | `8232ee7` | QA audit at `760f4b0` (docs only): black-box money races, refunds, 429/fail-open, SIGKILL recovery, projection convergence on an isolated stack; findings F-01–F-11 | [QA audit](../qa-audit-2026-09-11.md) |
| 2026-09-13 | `1d19764` · `e6472f7` | Both reviews fact-checked (F-01/F-03 mechanics confirmed, overstated coverage claims corrected); **test-gap closure F-04–F-08**, tests and `pom.xml` only, PIT gate widened to three adapter classes | [fact check](../evidence/review-fact-check-2026-09-13/summary.json), [test-gap evidence](../test-gap-evidence-2026-09-13.md) |
| 2026-09-14 | `6ba729d` | Full money QA at `e6472f7`: four regression cases added; manual money/adapter mutations detected; F-01/F-02/F-03 reproduced; outage recovery safe | [audit](../qa-audit-2026-09-14.md), [counts](../evidence/qa-2026-09-14/final-gate-summary.json) |
| 2026-09-14 | `5ffb0d2` | **Step 3 first slice, F-01**: cause-aware transaction-start 503 and safe unexpected-transaction 500 in `ApiProblems`; real stopped-DB recovery test | [F-01 evidence](../database-outage-f01.md) |
| 2026-09-15 | `863d63f` | **Step 4 recovery slice, F-02**: V5 durable Kafka quarantine, bounded retry, offset/commit/restart regressions, audited authoritative replay; full gate and expanded PIT pass | [F-02 evidence](../kafka-quarantine-f02.md) |
| 2026-09-15 | — | First full black-box/chaos audit at `863d63f` (artifact only): money correct; five non-money findings — projection corruption by oversized/coerced event integers (project F-03), blanket 503 on a NUL byte, balance read 500 during a DB outage, readiness UP with PostgreSQL stopped, lazily registered counters | [report](https://claude.ai/artifact/W3WREbMNzipZVEAVo6nzRC) |
| 2026-09-16 | `a3b7f9d` | **Step 4 validation slice, F-03**: exact event integer/long checks before writes; 79 parser and 3 real-listener regressions; full gate and expanded PIT pass | [F-03 evidence](../balance-projection-f03.md) |
| 2026-09-16 | — | Second full audit at `a3b7f9d` (nothing committed): gate with JaCoCo, whole-service PIT, 484 black-box checks in 22 scenarios; F-03 closure verified with 13 hostile events; the other four findings reproduced and given project ids Review F4, F-12, F-13, F-14 | [summary](../qa-audit-2026-09-16.md), [report](https://claude.ai/artifact/94jezPXrjVvVzUNHijyj7A) |
| 2026-09-13 → 09-17 | `f2a7e07` · `9b9ccd9` · `-v4` | Memory maintenance: split into requirements / state / implementation / verification / history (`-v2`), re-baselined on `5ffb0d2` (`-v3`) and on `a3b7f9d` with the re-audit recorded in-tree (`orchestrate/memory-mgt-v4`); superseded gate numbers and the archived plan removed from active notes | — |

## Closed findings

- **F1 / R1 quadratic trigger** and **R2 TEMP-table shadowing**: closed by V4 (`9ef2639`), verified 2026-09-10. TEMP privilege intentionally retained on `wallet_app`.
- **R3 / F2 recipient-balance disclosure and untested HTTP authorization**: closed by step 2 (`760f4b0`), verified 2026-09-10.
- **F-04–F-08 test-quality findings** (refund-lock blind spot, debit reversal never executed, missing player untested, status-only race assertions, production paths never run under `verify`): closed by `e6472f7`, verified 2026-09-13.
- **F-01 stopped-database HTTP error contract**: closed by `5ffb0d2`, verified 2026-09-14; classification limits in [F-01 evidence](../database-outage-f01.md). The read-path window it left is tracked as F-12.
- **F-02 poison Kafka record stall**: closed by `863d63f`, verified 2026-09-15; durable quarantine, three-attempt retry and audited replay. [Evidence and limits](../kafka-quarantine-f02.md).
- **F-03 oversized/coerced event integers**: closed by `a3b7f9d`, verified in the gate 2026-09-16 and black-box the same day (13 hostile events quarantined, projection unchanged). Pre-fix corrupted projections are not repaired. [Evidence and limits](../balance-projection-f03.md).

## Superseded evidence

Every gate before `a3b7f9d` is superseded by the 2026-09-16 re-audit gate in [state](state.md); the linked documents above retain their counts. JaCoCo 97.2% / all-class PIT 94.5% at `e6472f7`/`6ba729d` are superseded by 97.4% / 95.2% at `a3b7f9d` (both still scratch measurements, not the pom gate). Local load figures exclude HTTP, Redis and Kafka and set no capacity target ([build evidence](../build-evidence.md#local-load-measurement)). Raw logs under `/private/tmp`, session scratchpads and `target/` reports are ephemeral.

## Environment notes worth keeping

- Local gates ran on ARM64 macOS with OrbStack Docker, Maven 3.9.11, PIT 1.30.0, JaCoCo 0.8.13; Java 21.0.8 (GraalVM for the F-02/F-03 gates and the 2026-09-16 re-audit; the 2026-09-15 artifact audit used Corretto 21.0.5). The host default JDK is 24 and the unit suite passes on it, so nothing fails loudly when `JAVA_HOME` is wrong.
- `gh` cannot see `origin` from this machine ("Repository not found"); a GitHub-hosted CI run has never been confirmed.
- Commits on `main` may be tree-identical to their source branches (`5ffb0d2` = `coder/recovery-db-outage` `e552a24`; `6ba729d` = `qa/QA-full-project-verify` `4acb084`; `8232ee7` = `1b04d5e`; `a3b7f9d` = `qa/full-analyze-tests`); evidence files may cite the branch hash.
