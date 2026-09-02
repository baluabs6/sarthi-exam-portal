CREATE TABLE result_feedback (
    id BIGSERIAL PRIMARY KEY,
    roll_number VARCHAR(32) NOT NULL,
    helpful BOOLEAN NOT NULL,
    comment VARCHAR(1000),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_result_feedback_roll_number ON result_feedback (roll_number);
