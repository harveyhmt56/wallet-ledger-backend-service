-- Reproducible local-only credentials. Production provisions these roles separately.
CREATE ROLE wallet_migration LOGIN PASSWORD 'wallet_migration_local' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE wallet_app LOGIN PASSWORD 'wallet_app_local' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER DATABASE wallet_ledger OWNER TO wallet_migration;
ALTER SCHEMA public OWNER TO wallet_migration;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE wallet_ledger TO wallet_app;
