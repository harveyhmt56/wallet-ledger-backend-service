CREATE TABLE consumed_event (
    event_id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE wallet_projection (
    wallet_id UUID PRIMARY KEY,
    wallet_sequence BIGINT NOT NULL CHECK (wallet_sequence >= 0),
    balance BIGINT NOT NULL CHECK (balance >= 0)
);
GRANT SELECT, INSERT ON consumed_event TO wallet_app;
GRANT SELECT, INSERT, UPDATE ON wallet_projection TO wallet_app;
