-- Migration: schema-per-sandbox to database-per-sandbox (PR #21, issue #16)
--
-- init_postgres.sql only runs when Postgres initializes a fresh volume, so any environment
-- that existed before this change needs this file run once against sandbox_builder:
--
--   psql -h <host> -U <master-user> -d sandbox_builder -f migrations/2026-09-database-per-sandbox.sql
--
-- Safe to re-run: the rename is guarded and skips itself once applied.

-- 1. Rename the metadata column the app now expects.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'sandbox_instances' AND column_name = 'schema_name'
  ) THEN
    ALTER TABLE sandbox_instances RENAME COLUMN schema_name TO database_name;
  END IF;
END $$;

-- 2. Legacy sandbox cleanup (manual, per sandbox).
--
-- Sandboxes provisioned before this change live as schemas inside sandbox_builder, and the
-- new teardown path cannot remove them: DROP DATABASE finds no database by that name, and
-- DROP USER fails while the user still owns a schema, so deleting one of these from the UI
-- now leaves it in DELETE_FAILED. Their data is whatever people typed into the demo, so they
-- must be removed deliberately, not forgotten. List them:
--
--   SELECT nspname FROM pg_namespace WHERE nspname LIKE 'sandbox_sb_%';
--
-- Then, for each one you have confirmed is no longer needed:
--
--   DROP SCHEMA <name> CASCADE;
--   DROP USER IF EXISTS <name>;
--   DELETE FROM sandbox_instances WHERE database_name = '<name>';
--
-- These are deliberately not automated: dropping data should be a human decision per sandbox.
-- If the whole environment is disposable (local dev), recreating the Postgres volume from
-- scratch is the simpler path and makes this file unnecessary.

-- 3. Soft-delete tombstones (PR #21 review follow-up).
--
-- Successful teardown now keeps the sandbox row as a scrubbed DELETED tombstone (audit trail
-- of past demos, and the database name survives for orphaned-resource tracking) instead of
-- deleting it. The app reads and writes deleted_at for this, so the column must exist.
ALTER TABLE sandbox_instances ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
