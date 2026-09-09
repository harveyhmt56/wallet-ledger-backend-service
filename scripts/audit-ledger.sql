-- Run with psql -X --set=ON_ERROR_STOP=1 --file=scripts/audit-ledger.sql.
-- One consistent, bounded maintenance snapshot. A mismatch raises 23514 and exits nonzero.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path = pg_catalog, public, pg_temp;
SET LOCAL statement_timeout = '5min';
SET LOCAL transaction_timeout = '6min';
DO $$
DECLARE
    failures BIGINT;
    example TEXT;
BEGIN
    SELECT count(*), min(issue || ' (' || entity_id::TEXT || ')') INTO failures, example
        FROM public.audit_ledger_integrity();
    IF failures <> 0 THEN
        RAISE EXCEPTION 'ledger audit found % issue(s); example: %', failures, example
            USING ERRCODE = '23514', HINT = 'Read public.audit_ledger_integrity() for details; preserve history.';
    END IF;
    RAISE NOTICE 'ledger audit passed';
END;
$$;
COMMIT;
