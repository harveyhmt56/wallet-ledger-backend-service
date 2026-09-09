-- Flyway executes this migration in one transaction. Block writers while establishing
-- the induction base for incremental validation; keep the locks until replacement commits.
SET LOCAL search_path = pg_catalog, public, pg_temp;
LOCK TABLE public.player, public.ledger_account, public.wallet,
    public.journal_transaction, public.ledger_entry IN SHARE ROW EXCLUSIVE MODE;

-- A separate full audit: one statement/snapshot, never called on the posting path.
CREATE FUNCTION public.audit_ledger_integrity()
RETURNS TABLE (issue pg_catalog.text, entity_id pg_catalog.uuid)
LANGUAGE sql STABLE SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
    WITH ordered_entries AS MATERIALIZED (
        SELECT e.*,
            lag(e.wallet_sequence, 1, 0::BIGINT) OVER history AS previous_sequence,
            sum(e.amount::NUMERIC) OVER history AS running_balance
        FROM public.ledger_entry e
        WHERE e.wallet_id IS NOT NULL
        WINDOW history AS (PARTITION BY e.wallet_id ORDER BY e.wallet_sequence
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
    ), wallet_totals AS (
        SELECT e.wallet_id, sum(e.amount::NUMERIC) AS balance,
            count(*) AS entry_count, max(e.wallet_sequence) AS sequence
        FROM ordered_entries e GROUP BY e.wallet_id
    ), journal_totals AS (
        SELECT j.transaction_id, count(e.entry_id) AS entry_count,
            count(DISTINCT e.account_id) AS accounts, sum(e.amount::NUMERIC) AS balance,
            bool_and(abs(e.amount::NUMERIC) = j.amount AND e.currency = j.currency) AS valid_amounts
        FROM public.journal_transaction j
        LEFT JOIN public.ledger_entry e ON e.transaction_id = j.transaction_id
        GROUP BY j.transaction_id
    )
    SELECT 'wallet_ownership', w.wallet_id
    FROM public.wallet w LEFT JOIN public.ledger_account a ON a.account_id = w.account_id
    WHERE a.kind IS DISTINCT FROM 'PLAYER' OR a.player_id IS DISTINCT FROM w.player_id
    UNION ALL
    SELECT 'entry_metadata', e.entry_id
    FROM public.ledger_entry e JOIN public.ledger_account a ON a.account_id = e.account_id
    WHERE (a.kind = 'PLAYER' AND (e.wallet_id IS NULL OR e.wallet_sequence IS NULL OR e.balance_after IS NULL))
        OR (a.kind <> 'PLAYER' AND (e.wallet_id IS NOT NULL OR e.wallet_sequence IS NOT NULL OR e.balance_after IS NOT NULL))
    UNION ALL
    SELECT 'entry_account', e.entry_id
    FROM public.ledger_entry e LEFT JOIN public.wallet w ON w.wallet_id = e.wallet_id
    WHERE e.wallet_id IS NOT NULL AND e.account_id IS DISTINCT FROM w.account_id
    UNION ALL
    SELECT 'entry_sequence', e.entry_id FROM ordered_entries e
    WHERE e.wallet_sequence::NUMERIC <> e.previous_sequence::NUMERIC + 1
    UNION ALL
    SELECT 'entry_balance', e.entry_id FROM ordered_entries e
    WHERE e.balance_after::NUMERIC IS DISTINCT FROM e.running_balance
    UNION ALL
    SELECT 'wallet_tail', w.wallet_id
    FROM public.wallet w LEFT JOIN wallet_totals t ON t.wallet_id = w.wallet_id
    WHERE w.balance::NUMERIC <> coalesce(t.balance, 0)
        OR w.sequence <> coalesce(t.sequence, 0) OR w.sequence <> coalesce(t.entry_count, 0)
    UNION ALL
    SELECT 'journal_complete', j.transaction_id FROM journal_totals j
    WHERE j.entry_count <> 2 OR j.accounts <> 2 OR j.balance IS DISTINCT FROM 0::NUMERIC
        OR NOT coalesce(j.valid_amounts, false)
    UNION ALL
    SELECT 'refund_inverse', r.transaction_id
    FROM public.journal_transaction r
    LEFT JOIN public.journal_transaction original ON original.transaction_id = r.original_transaction_id
    WHERE r.operation = 'REFUND' AND (
        original.operation IS NULL OR original.operation NOT IN ('CREDIT', 'DEBIT')
        OR r.amount <> original.amount OR r.currency <> original.currency
        OR EXISTS (
            SELECT 1 FROM public.ledger_entry reversal
            WHERE reversal.transaction_id = r.transaction_id AND NOT EXISTS (
                SELECT 1 FROM public.ledger_entry e
                WHERE e.transaction_id = original.transaction_id
                    AND e.account_id = reversal.account_id
                    AND e.wallet_id IS NOT DISTINCT FROM reversal.wallet_id
                    AND e.currency = reversal.currency
                    AND e.amount::NUMERIC = -reversal.amount::NUMERIC
            )
        )
    );
$$;
REVOKE ALL ON FUNCTION public.audit_ledger_integrity() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.audit_ledger_integrity() TO wallet_app;

DO $$
DECLARE
    finding RECORD;
BEGIN
    SELECT * INTO finding FROM public.audit_ledger_integrity() LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'ledger integrity preflight failed: % (%)', finding.issue, finding.entity_id
            USING ERRCODE = '23514', HINT = 'Investigate existing history; do not rewrite it or skip this migration.';
    END IF;
END;
$$;

ALTER FUNCTION public.reject_history_mutation() SECURITY INVOKER;
ALTER FUNCTION public.reject_history_mutation() SET search_path = pg_catalog, public, pg_temp;

CREATE OR REPLACE FUNCTION public.check_complete_journal()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    header_amount BIGINT;
    header_currency VARCHAR(16);
    entry_count BIGINT;
    distinct_accounts BIGINT;
    total NUMERIC;
    valid_amounts BOOLEAN;
BEGIN
    SELECT j.amount, j.currency INTO header_amount, header_currency
        FROM public.journal_transaction j WHERE j.transaction_id = NEW.transaction_id;
    SELECT count(*), count(DISTINCT e.account_id), coalesce(sum(e.amount::NUMERIC), 0),
        bool_and(abs(e.amount::NUMERIC) = header_amount AND e.currency = header_currency)
        INTO entry_count, distinct_accounts, total, valid_amounts
        FROM public.ledger_entry e WHERE e.transaction_id = NEW.transaction_id;
    IF header_amount IS NULL OR entry_count <> 2 OR distinct_accounts <> 2
        OR total <> 0 OR NOT coalesce(valid_amounts, false) THEN
        RAISE EXCEPTION 'journal must contain two distinct balanced entries' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.check_wallet_ledger()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    current_wallet public.wallet%ROWTYPE;
    previous_balance BIGINT;
    tail_balance BIGINT;
    tail_sequence BIGINT;
BEGIN
    IF NEW.wallet_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM public.ledger_account a
            WHERE a.account_id = NEW.account_id AND a.kind = 'PLAYER') THEN
            RAISE EXCEPTION 'player entry requires wallet metadata' USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    -- Deferred NEW wallet images can be intermediate. Always read the final stored row.
    SELECT w.* INTO current_wallet FROM public.wallet w WHERE w.wallet_id = NEW.wallet_id;
    IF NOT FOUND OR NOT EXISTS (SELECT 1 FROM public.ledger_account a
        WHERE a.account_id = current_wallet.account_id AND a.player_id = current_wallet.player_id
            AND a.kind = 'PLAYER') THEN
        RAISE EXCEPTION 'wallet account ownership is invalid' USING ERRCODE = '23514';
    END IF;

    IF TG_TABLE_NAME = 'ledger_entry' THEN
        IF NEW.account_id <> current_wallet.account_id THEN
            RAISE EXCEPTION 'entry account does not match wallet' USING ERRCODE = '23514';
        END IF;
        IF NEW.wallet_sequence = 1 THEN
            previous_balance := 0;
        ELSE
            -- UNIQUE(wallet_id, wallet_sequence) supplies both this point lookup and the tail lookup.
            SELECT e.balance_after INTO previous_balance FROM public.ledger_entry e
                WHERE e.wallet_id = NEW.wallet_id AND e.wallet_sequence = NEW.wallet_sequence - 1;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'entry predecessor is missing' USING ERRCODE = '23514';
            END IF;
        END IF;
        -- Cast before adding: valid BIGINT operands can have an out-of-range sum.
        IF previous_balance::NUMERIC + NEW.amount::NUMERIC <> NEW.balance_after::NUMERIC THEN
            RAISE EXCEPTION 'entry balance does not match predecessor' USING ERRCODE = '23514';
        END IF;
    END IF;

    SELECT e.balance_after, e.wallet_sequence INTO tail_balance, tail_sequence
        FROM public.ledger_entry e WHERE e.wallet_id = NEW.wallet_id
        ORDER BY e.wallet_sequence DESC LIMIT 1;
    IF current_wallet.balance <> coalesce(tail_balance, 0)
        OR current_wallet.sequence <> coalesce(tail_sequence, 0) THEN
        RAISE EXCEPTION 'wallet balance or sequence does not match ledger tail' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
