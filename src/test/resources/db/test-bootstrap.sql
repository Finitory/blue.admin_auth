CREATE ROLE admin_auth_migration
    LOGIN PASSWORD 'test_migration_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

CREATE ROLE admin_auth_runtime
    LOGIN PASSWORD 'test_runtime_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

REVOKE ALL ON DATABASE admin_auth_db FROM PUBLIC;

GRANT CONNECT ON DATABASE admin_auth_db
    TO admin_auth_migration, admin_auth_runtime;

REVOKE ALL ON SCHEMA public FROM PUBLIC;

GRANT USAGE, CREATE ON SCHEMA public
    TO admin_auth_migration;

GRANT USAGE ON SCHEMA public
    TO admin_auth_runtime;

ALTER DEFAULT PRIVILEGES
    FOR ROLE admin_auth_migration
    IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLES TO admin_auth_runtime;
