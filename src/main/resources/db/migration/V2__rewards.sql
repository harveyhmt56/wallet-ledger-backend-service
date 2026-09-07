CREATE TABLE reward_definition (
    reward_id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    policy_version VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE action_completion (
    completion_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES player(player_id),
    reward_id UUID NOT NULL REFERENCES reward_definition(reward_id),
    source VARCHAR(100) NOT NULL CHECK (length(trim(source)) > 0),
    source_reference VARCHAR(200) NOT NULL CHECK (length(trim(source_reference)) > 0),
    actor VARCHAR(200) NOT NULL CHECK (length(trim(actor)) > 0),
    completed_at TIMESTAMPTZ NOT NULL,
    UNIQUE(source, source_reference)
);
CREATE TABLE reward_claim (
    completion_id UUID PRIMARY KEY REFERENCES action_completion(completion_id),
    player_id UUID NOT NULL REFERENCES player(player_id),
    reward_id UUID NOT NULL REFERENCES reward_definition(reward_id),
    transaction_id UUID NOT NULL UNIQUE REFERENCES journal_transaction(transaction_id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    policy_version VARCHAR(100) NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE daily_streak (
    player_id UUID PRIMARY KEY REFERENCES player(player_id),
    last_claim_date DATE,
    streak INTEGER NOT NULL DEFAULT 0 CHECK (streak >= 0),
    CHECK ((last_claim_date IS NULL) = (streak = 0))
);
CREATE TABLE daily_claim (
    player_id UUID NOT NULL REFERENCES player(player_id),
    claim_date DATE NOT NULL,
    streak INTEGER NOT NULL CHECK (streak > 0),
    amount BIGINT NOT NULL CHECK (amount > 0),
    policy_version VARCHAR(100) NOT NULL,
    transaction_id UUID NOT NULL UNIQUE REFERENCES journal_transaction(transaction_id),
    PRIMARY KEY(player_id, claim_date)
);
CREATE TABLE promotion (
    promotion_id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    claimed_count INTEGER NOT NULL DEFAULT 0 CHECK (claimed_count >= 0 AND claimed_count <= capacity),
    policy_version VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE promotion_claim (
    promotion_id UUID NOT NULL REFERENCES promotion(promotion_id),
    player_id UUID NOT NULL REFERENCES player(player_id),
    transaction_id UUID NOT NULL UNIQUE REFERENCES journal_transaction(transaction_id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    policy_version VARCHAR(100) NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(promotion_id, player_id)
);

INSERT INTO reward_definition(reward_id, name, amount, policy_version)
VALUES ('20000000-0000-0000-0000-000000000001', 'Demo mission completion', 100, 'mission-v1');
INSERT INTO promotion(promotion_id, name, amount, capacity, policy_version)
VALUES ('30000000-0000-0000-0000-000000000001', 'Demo first 100 players', 25, 100, 'promotion-v1');

GRANT SELECT ON reward_definition TO wallet_app;
GRANT SELECT, INSERT ON action_completion, reward_claim, daily_claim, promotion_claim TO wallet_app;
GRANT SELECT, INSERT, UPDATE ON daily_streak TO wallet_app;
GRANT SELECT ON promotion TO wallet_app;
GRANT UPDATE (claimed_count) ON promotion TO wallet_app;
