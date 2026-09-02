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

-- Sample data for local testing / demo
INSERT INTO results (roll_number, exam_id, name, score, status, declared_at) VALUES
    ('26104578912', 'NEET-UG-2026', 'Aarav Sharma', 612, 'PASS', now()),
    ('26104578913', 'NEET-UG-2026', 'Diya Patel', 545, 'PASS', now()),
    ('26104578914', 'NEET-UG-2026', 'Kabir Singh', 298, 'FAIL', now()),
    ('26205671201', 'JEE-MAIN-2026', 'Ishaan Reddy', 264, 'PASS', now()),
    ('26205671202', 'JEE-MAIN-2026', 'Ananya Iyer', 189, 'FAIL', now());
