CREATE TABLE player (
    player_id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ledger_account (
    account_id UUID PRIMARY KEY,
    player_id UUID UNIQUE REFERENCES player(player_id),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PLAYER', 'ISSUANCE', 'PURCHASE')),
    currency VARCHAR(16) NOT NULL DEFAULT 'COIN' CHECK (currency = 'COIN'),
    CHECK ((kind = 'PLAYER') = (player_id IS NOT NULL))
);
INSERT INTO ledger_account(account_id, kind) VALUES
    ('00000000-0000-0000-0000-000000000001', 'ISSUANCE'),
    ('00000000-0000-0000-0000-000000000002', 'PURCHASE');
CREATE TABLE wallet (
    wallet_id UUID PRIMARY KEY,
    player_id UUID NOT NULL UNIQUE REFERENCES player(player_id),
    account_id UUID NOT NULL UNIQUE REFERENCES ledger_account(account_id),
    balance BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0),
    sequence BIGINT NOT NULL DEFAULT 0 CHECK (sequence >= 0)
);
CREATE TABLE journal_transaction (
    transaction_id UUID PRIMARY KEY,
    operation VARCHAR(32) NOT NULL CHECK (operation IN ('CREDIT', 'DEBIT', 'TRANSFER', 'REFUND')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(16) NOT NULL DEFAULT 'COIN' CHECK (currency = 'COIN'),
    actor VARCHAR(200) NOT NULL CHECK (length(trim(actor)) > 0),
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    source VARCHAR(100) NOT NULL CHECK (length(trim(source)) > 0),
    reference VARCHAR(200) NOT NULL CHECK (length(trim(reference)) > 0),
    original_transaction_id UUID UNIQUE REFERENCES journal_transaction(transaction_id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(operation, source, reference),
    CHECK ((operation = 'REFUND') = (original_transaction_id IS NOT NULL))
);
CREATE TABLE ledger_entry (
    entry_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES journal_transaction(transaction_id),
    account_id UUID NOT NULL REFERENCES ledger_account(account_id),
    amount BIGINT NOT NULL CHECK (amount <> 0 AND amount <> '-9223372036854775808'::BIGINT),
    currency VARCHAR(16) NOT NULL DEFAULT 'COIN' CHECK (currency = 'COIN'),
    wallet_id UUID REFERENCES wallet(wallet_id),
    wallet_sequence BIGINT CHECK (wallet_sequence > 0),
    balance_after BIGINT CHECK (balance_after >= 0),
    UNIQUE(transaction_id, account_id),
    UNIQUE(wallet_id, wallet_sequence),
    CHECK ((wallet_id IS NOT NULL AND wallet_sequence IS NOT NULL AND balance_after IS NOT NULL)
        OR (wallet_id IS NULL AND wallet_sequence IS NULL AND balance_after IS NULL))
);
CREATE INDEX ledger_entry_account ON ledger_entry(account_id);
CREATE TABLE idempotency_request (
    actor VARCHAR(200) NOT NULL,
    request_key VARCHAR(200) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    status INTEGER,
    response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(actor, request_key),
    CHECK ((status IS NULL) = (response IS NULL))
);
CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL REFERENCES wallet(wallet_id),
    wallet_sequence BIGINT NOT NULL,
    journal_transaction_id UUID NOT NULL REFERENCES journal_transaction(transaction_id),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    lease_token UUID,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    UNIQUE(wallet_id, wallet_sequence)
);
CREATE INDEX outbox_pending ON outbox_event(created_at) WHERE delivered_at IS NULL;

CREATE FUNCTION reject_history_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'journal history is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER journal_immutable BEFORE UPDATE OR DELETE ON journal_transaction
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER journal_no_truncate BEFORE TRUNCATE ON journal_transaction
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER entry_immutable BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER entry_no_truncate BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();

CREATE FUNCTION check_complete_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal_id UUID;
    header_amount BIGINT;
    header_currency VARCHAR(16);
    entry_count BIGINT;
    distinct_accounts BIGINT;
    total NUMERIC;
    valid_amounts BOOLEAN;
BEGIN
    journal_id := CASE WHEN TG_TABLE_NAME = 'journal_transaction' THEN NEW.transaction_id ELSE NEW.transaction_id END;
    SELECT amount, currency INTO header_amount, header_currency
        FROM journal_transaction WHERE transaction_id = journal_id;
    SELECT count(*), count(DISTINCT account_id), coalesce(sum(amount::NUMERIC), 0),
        bool_and(abs(amount::NUMERIC) = header_amount AND currency = header_currency)
        INTO entry_count, distinct_accounts, total, valid_amounts
        FROM ledger_entry WHERE transaction_id = journal_id;
    IF entry_count <> 2 OR distinct_accounts <> 2 OR total <> 0 OR NOT coalesce(valid_amounts, false) THEN
        RAISE EXCEPTION 'journal must contain two distinct balanced entries' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER journal_complete AFTER INSERT ON journal_transaction
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_complete_journal();
CREATE CONSTRAINT TRIGGER entries_balanced AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_complete_journal();

CREATE FUNCTION check_wallet_ledger() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    target UUID;
    current_wallet wallet%ROWTYPE;
    total NUMERIC;
    entry_count BIGINT;
    highest_sequence BIGINT;
BEGIN
    target := NEW.wallet_id;
    IF target IS NULL THEN
        IF EXISTS (SELECT 1 FROM ledger_account WHERE account_id = NEW.account_id AND kind = 'PLAYER') THEN
            RAISE EXCEPTION 'player entry requires wallet metadata' USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;
    SELECT * INTO current_wallet FROM wallet WHERE wallet_id = target;
    IF NOT EXISTS (SELECT 1 FROM ledger_account WHERE account_id = current_wallet.account_id
        AND player_id = current_wallet.player_id AND kind = 'PLAYER') THEN
        RAISE EXCEPTION 'wallet account ownership is invalid' USING ERRCODE = '23514';
    END IF;
    SELECT coalesce(sum(amount::NUMERIC), 0), count(*), coalesce(max(wallet_sequence), 0)
        INTO total, entry_count, highest_sequence FROM ledger_entry WHERE wallet_id = target;
    IF current_wallet.balance::NUMERIC <> total OR current_wallet.sequence <> highest_sequence
        OR entry_count <> highest_sequence THEN
        RAISE EXCEPTION 'wallet balance or sequence does not match ledger' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM ledger_entry e WHERE e.wallet_id = target AND
        (e.account_id <> current_wallet.account_id OR e.balance_after::NUMERIC <>
            (SELECT sum(p.amount::NUMERIC) FROM ledger_entry p
             WHERE p.wallet_id = target AND p.wallet_sequence <= e.wallet_sequence))) THEN
        RAISE EXCEPTION 'entry balance or account does not match wallet' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER wallet_reconciled AFTER INSERT OR UPDATE ON wallet
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_wallet_ledger();
CREATE CONSTRAINT TRIGGER entry_wallet_reconciled AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_wallet_ledger();

GRANT USAGE ON SCHEMA public TO wallet_app;
GRANT SELECT, INSERT ON player, ledger_account, wallet, journal_transaction, ledger_entry TO wallet_app;
GRANT UPDATE (balance, sequence) ON wallet TO wallet_app;
GRANT SELECT, INSERT, UPDATE ON idempotency_request, outbox_event TO wallet_app;
