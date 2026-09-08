CREATE TABLE application_status_history (
    id BIGSERIAL PRIMARY KEY,
    roll_number TEXT NOT NULL,
    exam_id VARCHAR(64) NOT NULL,
    stage VARCHAR(32) NOT NULL, -- SUBMITTED | VERIFIED | ADMIT_CARD_ISSUED | RESULT_DECLARED
    note VARCHAR(500),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_application_status_roll_exam ON application_status_history (roll_number, exam_id);

-- Seed a baseline "SUBMITTED" stage for the existing demo roll numbers,
-- so the timeline isn't empty for the sample data already in the DB.
--
-- NOTE: as of the results-table encryption fix, V1 no longer inserts
-- demo rows via SQL (see V1's comment) — results is empty at migration
-- time, so this SELECT legitimately matches zero rows here and is a
-- harmless no-op. DemoDataSeeder.java inserts the matching
-- ApplicationStatusHistory rows itself, alongside the Result rows, once
-- the app starts.
INSERT INTO application_status_history (roll_number, exam_id, stage, note, changed_at)
SELECT roll_number, exam_id, 'RESULT_DECLARED', 'Result already declared', declared_at
FROM results;
