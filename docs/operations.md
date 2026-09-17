# Production operations

This page keeps the two repository-specific procedures that are not obvious from source alone: the populated V4 ledger migration/audit and Kafka quarantine replay. Local Compose is a development environment, not a production deployment.

## Populated V4 migration

[`V4__indexed_ledger_integrity.sql`](../src/main/resources/db/migration/V4__indexed_ledger_integrity.sql) audits existing history while holding `SHARE ROW EXCLUSIVE` locks on the ledger tables, then replaces the integrity functions in the same transaction. PostgreSQL documents that this lock conflicts with writers' `ROW EXCLUSIVE` locks. A failed preflight raises SQLSTATE `23514` and rolls the migration back; investigate the named issue and preserve the history rather than editing or bypassing it.

Production procedure:

1. Quiesce all writers and drain in-flight money transactions. A rolling restart alone does not guarantee this.
2. Run Flyway migrate and validate using the migration role and unchanged versioned migrations. Set migration-session lock, statement, and transaction limits from a representative rehearsal; request-pool limits do not apply to Flyway's separate datasource.
3. Confirm V4 is applied and run the bounded audit below. Only then start serving instances with `SPRING_FLYWAY_ENABLED=false`; enforce the separate migration step and keep migration credentials out of serving instances.
4. Restore writes, schedule the audit, and alert on any nonzero exit.

Run the checked-in read-only audit through a protected libpq service definition:

```sh
psql -X 'service=wallet-ledger-audit' \
  --set=ON_ERROR_STOP=1 --file=scripts/audit-ledger.sql
```

[`scripts/audit-ledger.sql`](../scripts/audit-ledger.sql) uses one repeatable-read snapshot, a five-minute statement timeout, and a six-minute transaction timeout. `public.audit_ledger_integrity()` checks ownership, metadata, sequence continuity, running balances, wallet totals, complete journals, and inverse refunds. No returned rows means no findings; it does not prove every service-level operation rule. Run it after migration, recovery, or imports and on a regular schedule. Route every nonzero exit—including connection failure or timeout—to database operations.

Primary references: [PostgreSQL table-lock conflicts](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-TABLES) and [client timeout semantics](https://www.postgresql.org/docs/17/runtime-config-client.html).

## Kafka quarantine

The balance consumer stores failed records in `kafka_quarantine` before allowing the source offset to advance. Permanent input failures quarantine immediately; other failures receive three delivery attempts. If the quarantine transaction fails, recovery fails and the source record remains eligible for delivery. Rows are uniquely identified by consumer group, topic, partition, and offset and are not rewritten by duplicate recovery.

Alert on increases in `wallet.kafka.quarantined` and `wallet.kafka.quarantine.failures`, then check consumer lag and PostgreSQL availability. The counters are diagnostic; the PostgreSQL rows are durable. Inspect stored bytes only with approved database access and treat them as untrusted data.

Read-only triage:

```sql
SELECT consumer_group, topic, partition_id, record_offset,
       record_timestamp, error_type, quarantined_at,
       octet_length(record_key) AS key_bytes,
       octet_length(payload) AS payload_bytes
FROM public.kafka_quarantine
ORDER BY quarantined_at DESC
LIMIT 50;
```

### Replay authority

Replay only an unchanged payload from an authoritative `outbox_event` after confirming its wallet, sequence, and journal transaction belong to the incident. Never repair a payload by editing amounts or identifiers. If there is no authoritative match, preserve the quarantine row and investigate; this runbook cannot repair an already-corrupted projection.

Use a named operator login with `SELECT` on quarantine, outbox, consumed events, projection, and replay attempts. Grant `INSERT` on only these replay-attempt columns: `attempt_id`, `consumer_group`, `topic`, `partition_id`, `record_offset`, `source_event_id`, `approved_payload`, and `reason`. Do not grant updates/deletes or permission to override server-generated `requested_by` and `requested_at`. The deployment must provision this identity and Kafka TLS/authentication separately.

### Save intent and export the approved payload

Use PostgreSQL 17 `psql`, `uuidgen`, and deployment-protected client configuration. Replace every `REPLACE_…` value after review:

```sh
set -eu
umask 077
export PGSERVICE='wallet-replay-operator'
export PGCLIENTENCODING='UTF8'
quarantine_group='wallet-projection-demo'
quarantine_topic='wallet.balance-changed.v1'
quarantine_partition='REPLACE_PARTITION'
quarantine_offset='REPLACE_OFFSET'
source_event_id='REPLACE_AUTHORITATIVE_EVENT_UUID'
replay_reason='REPLACE_INCIDENT_AND_APPROVAL_REASON'
replay_attempt_id="$(uuidgen)"
replay_file="$(mktemp "${TMPDIR:-/tmp}/wallet-replay.XXXXXXXX")"

psql -X -qAt -v ON_ERROR_STOP=1 \
  -v group_id="$quarantine_group" -v topic="$quarantine_topic" \
  -v partition_id="$quarantine_partition" -v record_offset="$quarantine_offset" \
  -v event_id="$source_event_id" -v reason="$replay_reason" \
  -v attempt_id="$replay_attempt_id" > "$replay_file" <<'SQL'
BEGIN;
INSERT INTO public.kafka_quarantine_replay_attempt
    (attempt_id, consumer_group, topic, partition_id, record_offset,
     source_event_id, approved_payload, reason)
SELECT :'attempt_id'::uuid, q.consumer_group, q.topic, q.partition_id,
       q.record_offset, o.event_id, o.payload, :'reason'
FROM public.kafka_quarantine q
CROSS JOIN public.outbox_event o
WHERE q.consumer_group = :'group_id'
  AND q.topic = :'topic'
  AND q.topic = 'wallet.balance-changed.v1'
  AND q.partition_id = :'partition_id'::integer
  AND q.record_offset = :'record_offset'::bigint
  AND o.event_id = :'event_id'::uuid
  AND o.payload->>'eventId' = o.event_id::text
  AND o.payload->>'walletId' = o.wallet_id::text
  AND o.payload->>'walletSequence' = o.wallet_sequence::text
  AND o.payload->>'journalTransactionId' = o.journal_transaction_id::text
RETURNING attempt_id AS saved_attempt_id
\gset
COMMIT;
SELECT (approved_payload->>'walletId') || chr(9) || approved_payload::text
FROM public.kafka_quarantine_replay_attempt
WHERE attempt_id = :'saved_attempt_id'::uuid;
SQL
test -s "$replay_file"
printf 'Replay intent: %s\nProtected artifact: %s\n' \
  "$replay_attempt_id" "$replay_file"
```

The query must return exactly one row before `\gset`; it copies the authoritative payload unchanged. If commit is ambiguous, look up the attempt UUID before doing anything else. If export fails after commit, export `approved_payload` from that same attempt rather than reconstructing it. Protect the file and apply the incident-data retention policy.

### Publish and verify

```sh
kafka_home='/opt/kafka'
kafka_brokers='REPLACE_BOOTSTRAP_SERVERS'
kafka_operator_config='/secure/path/operator-producer.properties'
"$kafka_home/bin/kafka-console-producer.sh" \
  --bootstrap-server "$kafka_brokers" \
  --topic "$quarantine_topic" \
  --producer.config "$kafka_operator_config" \
  --producer-property acks=all \
  --producer-property enable.idempotence=true \
  --sync --property parse.key=true --property "key.separator=$(printf '\t')" \
  < "$replay_file"
```

An acknowledged publish does not prove consumption. Verify with the saved attempt UUID:

```sh
psql -X -v ON_ERROR_STOP=1 -v attempt_id="$replay_attempt_id" <<'SQL'
SELECT r.attempt_id, r.source_event_id, c.consumed_at,
       p.wallet_sequence AS projection_sequence,
       (r.approved_payload->>'walletSequence')::bigint AS replay_sequence,
       p.balance AS projection_balance,
       (c.event_id IS NOT NULL AND
        c.wallet_id = (r.approved_payload->>'walletId')::uuid AND
        p.wallet_sequence >= (r.approved_payload->>'walletSequence')::bigint AND
        (p.wallet_sequence > (r.approved_payload->>'walletSequence')::bigint OR
         p.balance = (r.approved_payload->>'balanceAfter')::bigint))
         AS consumed_and_sequence_reached
FROM public.kafka_quarantine_replay_attempt r
LEFT JOIN public.consumed_event c ON c.event_id = r.source_event_id
LEFT JOIN public.wallet_projection p
  ON p.wallet_id = (r.approved_payload->>'walletId')::uuid
WHERE r.attempt_id = :'attempt_id'::uuid;
SQL
```

Require the expected consumed-event row and a projection sequence at least as new as the replay. At an equal sequence, the balance must match; a newer sequence legitimately keeps its newer balance. An ambiguous publish may resend the same protected artifact because its event ID is deduplicated. Keep the original quarantine and replay-intent rows.

Production topics are still an open deployment requirement: local automatic creation uses replication factor one. Provision and verify the real topic, producer `acks=all`, replication, and minimum in-sync replicas; do not infer durability from application configuration alone. See [Kafka producer acknowledgements](https://kafka.apache.org/39/configuration/producer-configs/) and [broker `min.insync.replicas`](https://kafka.apache.org/39/configuration/broker-configs/).
