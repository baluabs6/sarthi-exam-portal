CREATE TABLE grievances (
    id BIGSERIAL PRIMARY KEY,
    ticket_ref VARCHAR(36) NOT NULL UNIQUE,
    roll_number VARCHAR(32) NOT NULL,
    exam_id VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RAISED', -- RAISED | UNDER_REVIEW | RESOLVED | REJECTED
    admin_note VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_grievances_roll_number ON grievances (roll_number);
CREATE INDEX idx_grievances_status ON grievances (status);
