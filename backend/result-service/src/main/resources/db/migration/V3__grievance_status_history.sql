CREATE TABLE grievance_status_history (
    id BIGSERIAL PRIMARY KEY,
    grievance_id BIGINT NOT NULL REFERENCES grievances(id),
    previous_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    admin_note VARCHAR(2000),
    changed_by_ip VARCHAR(64),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_grievance_history_grievance_id ON grievance_status_history (grievance_id);
