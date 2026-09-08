-- Widen columns that now store encrypted (ciphertext/base64) values
-- instead of plaintext, same reason as V5__encrypt_pii_columns.sql:
-- base64-encoded AES output is longer than the original plaintext and
-- doesn't fit the original VARCHAR bounds.
--
-- IMPORTANT — this migration only widens the columns. It does NOT
-- re-encrypt existing plaintext rows (Flyway SQL has no access to
-- PiiEncryptionService's key, so it can't do that part). On a fresh
-- install (e.g. this repo's seed data in V1) that's irrelevant — the
-- app writes ciphertext from the first insert. On an existing
-- production database that already has plaintext rows, a one-time data
-- migration job (read each row, encrypt in application code, write it
-- back) must run before or immediately after deploying the code change
-- that adds @Convert to Result.rollNumber/name — otherwise
-- PiiEncryptionService will fail to decrypt what it reads back, since
-- plaintext isn't valid AES-GCM/ECB ciphertext.
ALTER TABLE results ALTER COLUMN roll_number TYPE TEXT;
ALTER TABLE results ALTER COLUMN name TYPE TEXT;

ALTER TABLE application_status_history ALTER COLUMN roll_number TYPE TEXT;
-- (already TEXT from V8, kept here as a no-op for documentation/clarity
-- of what changed alongside the encryption fix)
