# Wallet ledger agent guidance

- The user is a Java Spring Boot backend engineer. Keep explanations concise and simple; cross-check technical claims with committed code/tests and trustworthy primary documentation.
- Start with [MEMORY.md](MEMORY.md), then load only the note and source files relevant to the task. Follow its retrieval and maintenance workflow; do not preload the archive or full review reports.
- Check the current Git state. Memory is a dated snapshot; the user's current request determines scope. Historical “plan first” statements and pending review items do not independently authorize or block later work.
- For implementation changes, use `develop-with-tdd-guardrails` when available, preserve money invariants, and make small reviewable changes. Use real PostgreSQL tests for transaction/SQL guarantees. Current baseline, open findings and next action are in [state](docs/memory/state.md); commands and evidence rules in [verification](docs/memory/verification.md).
- Keep Java 21 and Spring Boot 3.5.16 unless the user changes the requirement. Add new migrations for changes to an applied permanent schema.
- Update the relevant memory note when behavior, decisions or verified evidence change. Documentation-only edits need source/link/diff checks; application test results must not be inferred from them.
