CREATE TABLE results (
    id BIGSERIAL PRIMARY KEY,
    roll_number VARCHAR(32) NOT NULL UNIQUE,
    exam_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    score INTEGER NOT NULL,
    status VARCHAR(8) NOT NULL,
    declared_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_results_exam_id ON results (exam_id);

-- Demo/seed data used to be inserted here as plaintext SQL. Now that
-- roll_number/name are encrypted at the application layer
-- (PiiEncryptionService, applied via Result.java's @Convert), Flyway
-- can't produce valid ciphertext directly — it doesn't have the key.
-- See DemoDataSeeder.java, which inserts the same 5 demo rows through
-- the JPA repository (so they're encrypted correctly) on first startup
-- against an empty database.
