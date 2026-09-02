-- Widen columns that now store encrypted (ciphertext/base64) values
-- instead of plaintext — base64-encoded AES output is longer than the
-- original plaintext and doesn't fit the original VARCHAR bounds.
ALTER TABLE grievances ALTER COLUMN roll_number TYPE TEXT;
ALTER TABLE grievances ALTER COLUMN message TYPE TEXT;
ALTER TABLE grievances ALTER COLUMN admin_note TYPE TEXT;

ALTER TABLE result_feedback ALTER COLUMN roll_number TYPE TEXT;
ALTER TABLE result_feedback ALTER COLUMN comment TYPE TEXT;
ALTER TABLE result_feedback ADD COLUMN urgency_score INTEGER NOT NULL DEFAULT 0;
