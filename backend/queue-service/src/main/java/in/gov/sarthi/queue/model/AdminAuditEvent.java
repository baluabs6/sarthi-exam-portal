package in.gov.sarthi.queue.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Records staff/admin actions (e.g. tuning the live admission rate),
 * distinct from candidate queue events in QueueAuditEvent. The raw
 * admin key is never stored here — only which IP performed the action
 * and what changed — so this collection stays safe to review without
 * itself becoming a credential leak.
 */
@Document(collection = "admin_audit_events")
public class AdminAuditEvent {

    @Id
    private String id;

    private String action;      // e.g. "ADMIT_RATE_CHANGED"
    private String detail;      // human-readable summary of what changed
    private String sourceIp;
    private Instant timestamp;

    public AdminAuditEvent() {}

    public AdminAuditEvent(String action, String detail, String sourceIp) {
        this.action = action;
        this.detail = detail;
        this.sourceIp = sourceIp;
        this.timestamp = Instant.now();
    }

    public String getId() { return id; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public String getSourceIp() { return sourceIp; }
    public Instant getTimestamp() { return timestamp; }
}
