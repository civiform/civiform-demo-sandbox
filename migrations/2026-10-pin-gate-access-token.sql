-- ============================================================
-- PIN gate redesign (PR #35)
--
-- init_postgres.sql only runs when Postgres initializes a fresh volume, so any
-- sandbox_builder database that existed before this change needs this file run
-- once:
--
--   psql -h <host> -U <master-user> -d sandbox_builder -f migrations/2026-10-pin-gate-access-token.sql
--
-- Idempotent — safe to run more than once. The same statements are folded into
-- init_postgres.sql so that fresh volumes come up identical to migrated ones.
--
-- Without this migration, creating a sandbox fails on the INSERT in
-- SandboxRepository.save() with "column access_token does not exist".
-- ============================================================

-- 1. Per-sandbox cookie secret.
--
-- The PIN gate no longer sets a fixed cookie value. Entering the correct PIN
-- sets sb_access_<id>=<access_token>, and the sandbox's ALB allow rule matches
-- on that exact value, so a cookie cannot be forged by knowing only the sandbox
-- id. NOT NULL DEFAULT '' matches init_postgres.sql and lets the column be added
-- to a populated table without a rewrite.
ALTER TABLE sandbox_instances
  ADD COLUMN IF NOT EXISTS access_token VARCHAR(64) NOT NULL DEFAULT '';

-- 2. Backfill live rows.
--
-- Sandboxes provisioned before this change have ALB rules without a cookie
-- condition, so they do not consult the token today. They would the moment the
-- current terraform/sandbox module is re-applied to them: the allow rule's
-- condition is "*sb_access_<id>=<token>*", and with an empty token that becomes
-- "*sb_access_<id>=*", which any cookie bearing the right name satisfies — a
-- gate that opens for anyone who knows the sandbox id. Giving every live row a
-- real token now closes that path. Same format the builder generates
-- (UUID.randomUUID()), so nothing downstream can tell the difference.
--
-- Tombstones are excluded on purpose: softDelete scrubs access_token to '' and
-- that must stay scrubbed.
UPDATE sandbox_instances
   SET access_token = gen_random_uuid()::text
 WHERE deleted_at IS NULL
   AND access_token = '';

-- 3. Two listener rule priorities per sandbox.
--
-- Each sandbox now attaches two rules to the shared HTTPS listener:
--   listener_priority      allow:    host matches AND cookie matches → forward
--   listener_priority + 1  redirect: host matches                    → PIN gate
-- so the allocator must step by 2 or the next sandbox's allow rule would land
-- on the previous sandbox's redirect slot and the apply would fail with
-- PriorityInUse.
--
-- Safe to switch on a live sequence. Every priority handed out so far is
-- <= last_value, the next nextval() returns last_value + 2, and that sandbox's
-- redirect rule takes last_value + 3 — strictly above anything an existing
-- single-rule sandbox holds. No parity requirement: the pair just has to be
-- consecutive and unused.
ALTER SEQUENCE sandbox_listener_priority_seq INCREMENT BY 2;

-- If any listener rules were ever created outside the builder (by hand, or by
-- an aborted apply that was cleaned up manually), the sequence does not know
-- about them. Move it past the highest such priority before the next
-- provision:
--
--   SELECT setval('sandbox_listener_priority_seq', <highest-manual-priority>);

-- ── Verification ─────────────────────────────────────────────
-- Expect one row: access_token | character varying | 64 | ''::character varying
--   SELECT column_name, data_type, character_maximum_length, column_default
--     FROM information_schema.columns
--    WHERE table_name = 'sandbox_instances' AND column_name = 'access_token';
--
-- Expect increment_by = 2:
--   SELECT last_value, increment_by FROM pg_sequences
--    WHERE sequencename = 'sandbox_listener_priority_seq';
--
-- Expect no rows — every live sandbox has a token:
--   SELECT id FROM sandbox_instances
--    WHERE deleted_at IS NULL AND access_token = '';
