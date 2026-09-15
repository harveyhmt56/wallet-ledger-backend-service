# F-02: durable Kafka quarantine and operator replay

## Behavior and boundary

The balance consumer quarantines an invalid record in PostgreSQL so later records in its Kafka partition can proceed. `IllegalArgumentException` and Spring Kafka's existing fatal classifications go straight to recovery; other listener failures receive two retries, one second apart. Changing exception type does not reset that limit of three delivery attempts before recovery. Recovery returns only after its database transaction commits. A quarantine insert or commit failure escapes recovery, so the failed offset remains eligible for delivery and later records in that partition wait. This follows Spring Kafka 3.3's [error-handler recovery and retry contract](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html#default-error-handler).

`kafka_quarantine` retains the consumer group, topic, partition, offset, listener key/payload stored as UTF-8 bytes (including null and NUL characters), Kafka record timestamp, exception class and quarantine time. The configured string deserializers have already decoded the Kafka wire bytes, so this is not a byte-for-byte archive of invalid UTF-8. Its primary key is `(consumer_group, topic, partition_id, record_offset)`. `INSERT ... ON CONFLICT DO NOTHING` makes repeated recovery of that source record harmless without rewriting the first evidence. PostgreSQL documents [conflict handling and its privilege requirements](https://www.postgresql.org/docs/17/sql-insert.html). A crash after the database commit and before Kafka offset acknowledgement can therefore repeat recovery safely.

The runtime role has only `SELECT, INSERT` on quarantine and no privileges on `kafka_quarantine_replay_attempt`. The original quarantine remains unchanged by replay. Replay attempts record an approved payload snapshot, source coordinates, authoritative outbox event, incident reason, time and the authenticated database `session_user`. They record **intent**, not delivery success. PostgreSQL [table privileges](https://www.postgresql.org/docs/17/ddl-priv.html) underpin this separation; owners and database administrators remain privileged.

This change preserves event-ID deduplication and the projection's newer-sequence/absolute-balance rule. F-03 remains open: oversized event integers are a separate validation defect. Never repair replay data by manually changing numbers, `eventId`, `walletId` or `walletSequence`.

## Detection and triage

- Alert on increases in `wallet.kafka.quarantined` (new durable rows) and `wallet.kafka.quarantine.failures` (quarantine write/commit failures). A duplicate recovery does not count as a new quarantined record. These process counters are diagnostic; PostgreSQL rows are the durable record.
- Check consumer lag and database availability. A quarantine-store outage intentionally blocks the affected partition; restore that dependency before trying replay.
- The `KafkaQuarantine` logger records exception class names without exception messages or record bodies. This is not a redaction guarantee for Spring Kafka or other framework logs. Inspect stored record bytes only through approved database access; treat them as untrusted data.

Example read-only triage query:

```sql
SELECT consumer_group, topic, partition_id, record_offset,
       record_timestamp, error_type, quarantined_at,
       octet_length(record_key) AS key_bytes,
       octet_length(payload) AS payload_bytes
FROM public.kafka_quarantine
ORDER BY quarantined_at DESC
LIMIT 50;
```

## Operator replay

### 1. Establish authority and the source

Use a named operator login provisioned by the deployment, with database/schema access, `SELECT` on quarantine, outbox, consumed events, projection and replay attempts. Grant replay-attempt `INSERT` only on these input columns: `attempt_id`, `consumer_group`, `topic`, `partition_id`, `record_offset`, `source_event_id`, `approved_payload`, `reason`. Exclude `requested_by` and `requested_at` so the operator cannot override their server defaults; do not also grant table-level `INSERT`, including through inherited roles. Give it no update/delete/truncate permission on either quarantine table. Connect directly as that named login so `session_user` identifies the person; do not reuse `wallet_app` or a shared migration login. The migration does not create an operator login or an admin HTTP endpoint.

Record the incident and approval reason. Inspect the quarantined source and identify its matching `outbox_event` using authoritative wallet/journal evidence. Merely finding an outbox row with an ID supplied by an untrusted record does not establish a match. Confirm the selected event's wallet, sequence and journal transaction belong to the incident. Fix the consumer/dependency cause before replaying an unchanged authoritative event.

If there is no authoritative outbox match, **do not replay**. Keep the evidence and escalate for investigation. If F-03 may already have corrupted the projection, use a separate reviewed reconciliation/repair procedure; this runbook cannot repair a falsely advanced sequence or an already-consumed wrong payload.

### 2. Save replay intent and export the approved snapshot

Run this in a dedicated shell with PostgreSQL 17 `psql`, `uuidgen`, and Kafka 3.9 tools installed. Replace the explicit placeholders with reviewed incident values. `PGSERVICE` must name a deployment-configured connection for the named operator; credentials belong in protected client configuration, not command arguments. The protected Kafka configuration file must contain the deployment's authentication/TLS settings and permission to produce to the balance topic.

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
printf 'Replay intent: %s\nProtected artifact: %s\n' "$replay_attempt_id" "$replay_file"
```

The SQL copies the outbox payload unchanged. `\gset` requires exactly one returned row; a missing quarantine/source match stops the script before commit or publication. The export is one UTF-8 line: wallet UUID key, a tab, then JSON. `jsonb::text` escapes embedded control characters in JSON strings. See PostgreSQL's [psql variables and `\gset`](https://www.postgresql.org/docs/17/app-psql.html).

Record the attempt UUID in the incident. If the connection fails during commit, first look up that UUID to determine whether intent was saved. If export fails after commit, export `approved_payload` from that same saved attempt with the final `SELECT`; do not reconstruct or edit the payload. Keep the artifact protected and apply the deployment's incident-data retention policy.

### 3. Publish and verify

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

Kafka 3.9's [console producer implementation](https://github.com/apache/kafka/blob/3.9.1/core/src/main/scala/kafka/tools/ConsoleProducer.scala) supports keyed lines, protected producer configuration and synchronous sends. A successful command means the producer received an acknowledgement; it does not prove projection consumption. Log the command outcome and time in the incident, keeping credentials and payload out of general logs. After an ambiguous send, resend the **same protected artifact**; its preserved event ID makes duplicate consumption harmless. Record each resend in the incident. A separately approved replay should create a new attempt row.

Check database consumption using the same attempt UUID:

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

Require a consumed-event row for the expected wallet and a projection sequence at least as new as the replayed snapshot. At an equal sequence, the balance must match the approved snapshot; a newer sequence legitimately retains its newer balance. Confirm continuing consumption/lag recovery and investigate any newly quarantined replay. This query is consumption evidence, not a full balance-integrity audit; prior F-03 corruption needs separate investigation. Leave the original quarantine and intent rows in place, and attach verification results to the incident.

## Fresh verification evidence

Verified 2026-09-15 against `9b9ccd9` plus the F-02 working-tree changes on `coder/mq-issue-fix`. Environment: Java 21.0.8 (GraalVM), Maven 3.9.11, Spring Boot 3.5.16 / Spring Kafka 3.3.16, disposable PostgreSQL 17.6 and Kafka 3.9.1 containers on OrbStack. No Compose or live services were used.

| Check | Observed result |
| --- | --- |
| Parser baseline before changes | 37 unit cases passed |
| Red: malformed record followed by valid same-partition event | Expected projection count 1 stayed 0 for 20 seconds; listener retry configuration was still unchanged |
| Red: alternating retryable exception types | Third attempt remained unrecovered; adding `setResetStateOnExceptionChange(false)` made the regression pass |
| Focused green | 49 unit cases and 6 real listener integration cases; zero failures/errors/skips |
| Focused PIT, new quarantine and Kafka configuration | 14 KILLED, 2 NO_COVERAGE; 94% line coverage; no survivors/timeouts/errors |
| Final `spotless:apply clean verify -Pmutation` | 129 unit + 274 integration cases; zero failures/errors/skips; build/format pass; 87 seconds |
| Final expanded PIT gate | 82 KILLED, 2 NO_COVERAGE (98% mutation score); 154/162 lines; no survivors/timeouts/errors |
| SQL mutation cases within the integration total | 12/12 passed |

The two uncovered mutations replace the return values of unchanged `MessagingConfiguration.balanceTopic` and `relay` with null. All 14 covered mutations in the changed Kafka classes were killed, including removal of permanent-error classification, retry-budget preservation, transaction propagation and metric increments. The existing 80% mutation/coverage thresholds were retained; no exclusions were added. PIT does not mutate the SQL strings. Real PostgreSQL tests establish commit rollback, primary-key deduplication and runtime permission behavior.

The six [BalanceListenerIT](../src/test/java/com/example/walletledger/messaging/kafka/BalanceListenerIT.java) cases cover existing duplicate/stale delivery; malformed JSON, unsupported schema, NUL and tombstone quarantine followed by progress on two partitions; a deferred quarantine commit failure with retained offset; restart with the source offset rewound to model redelivery after DB commit; transient failures repaired before retry exhaustion or quarantined after three attempts; and audited authoritative replay with duplicate delivery, one consumed event, unchanged wallet funds and preserved poison evidence. The replay test uses the application Kafka producer after exercising the audit/export SQL; the operator CLI instructions were syntax/source-checked, not executed against a deployment.

Commands (with `JAVA_HOME` set to Java 21):

```sh
./mvnw -B -ntp -Dtest=BalanceProjectionTest test
./mvnw -B -ntp test-compile failsafe:integration-test failsafe:verify \
  '-Dit.test=BalanceListenerIT#poisonRecordsAreDurablyQuarantinedAndLaterRecordsProgressOnBothPartitions'
./mvnw -B -ntp spotless:apply \
  -Dtest=KafkaQuarantineTest,MessagingConfigurationTest,BalanceProjectionTest test \
  failsafe:integration-test failsafe:verify -Dit.test=BalanceListenerIT
./mvnw -B -ntp -Pmutation \
  '-DtargetClasses=com.example.walletledger.messaging.kafka.KafkaQuarantine,com.example.walletledger.messaging.kafka.MessagingConfiguration*' \
  org.pitest:pitest-maven:mutationCoverage
./mvnw -B -ntp spotless:apply clean verify -Pmutation
```

Reports were inspected in `target/surefire-reports`, `target/failsafe-reports` and `target/pit-reports/mutations.xml`; generated reports and `/tmp/f02-*.log` are ephemeral. Final source/test/migration changes passed the complete pipeline; subsequent edits only update documentation. F-03 validation, multi-broker durability, competing relay leases, deployment alert rules/operator provisioning and hosted CI remain outside this evidence.
