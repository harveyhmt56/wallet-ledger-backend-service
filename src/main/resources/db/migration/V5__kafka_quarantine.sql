CREATE TABLE kafka_quarantine (
    consumer_group TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
    record_offset BIGINT NOT NULL CHECK (record_offset >= 0),
    record_key BYTEA,
    payload BYTEA,
    record_timestamp BIGINT NOT NULL,
    error_type TEXT NOT NULL,
    quarantined_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_group, topic, partition_id, record_offset)
);

-- Original evidence is append-only for the application role. BYTEA also retains NULs.
GRANT SELECT, INSERT ON kafka_quarantine TO wallet_app;

-- An operator records intent before sending an approved authoritative event again.
-- Runtime credentials cannot create or alter replay audit records.
CREATE TABLE kafka_quarantine_replay_attempt (
    attempt_id UUID PRIMARY KEY,
    consumer_group TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    source_event_id UUID NOT NULL REFERENCES outbox_event(event_id),
    approved_payload JSONB NOT NULL,
    reason TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    requested_by TEXT NOT NULL DEFAULT session_user,
    FOREIGN KEY (consumer_group, topic, partition_id, record_offset)
        REFERENCES kafka_quarantine(consumer_group, topic, partition_id, record_offset)
);
