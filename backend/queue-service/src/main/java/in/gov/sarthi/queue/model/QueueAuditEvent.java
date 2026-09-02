package in.gov.sarthi.queue.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB is used here (rather than Postgres) specifically because this
 * is a high-volume, append-only, schema-flexible event stream — exactly
 * what a document store is good at, and it keeps this write traffic off
 * the relational database that result-service depends on for
 * transactional correctness.
 */
@Document(collection = "queue_audit_events")
public class QueueAuditEvent {

    @Id
    private String id;
    private String queueId;
    private String examId;
    private String rollNumber;
    private String eventType; // JOINED | ADMITTED | STATUS_BROADCAST
    private Instant timestamp;
    private Integer positionAtEvent;

    /**
     * Hash-chained audit trail: each event's hash is a SHA-256 digest of
     * its own fields plus the previous event's hash (per exam). Anyone
     * with direct Mongo access could otherwise edit or delete an audit
     * row without a trace; with chaining, altering or removing any one
     * event breaks every hash after it, so tampering becomes detectable
     * by simply re-walking the chain — see QueueManagerService.
     */
    private String prevHash;
    private String hash;

    public QueueAuditEvent() {}

    public QueueAuditEvent(String queueId, String examId, String rollNumber, String eventType, Integer positionAtEvent) {
        this.queueId = queueId;
        this.examId = examId;
        this.rollNumber = rollNumber;
        this.eventType = eventType;
        this.positionAtEvent = positionAtEvent;
        this.timestamp = Instant.now();
    }

    public String getId() { return id; }
    public String getQueueId() { return queueId; }
    public String getExamId() { return examId; }
    public String getRollNumber() { return rollNumber; }
    public String getEventType() { return eventType; }
    public Instant getTimestamp() { return timestamp; }
    public Integer getPositionAtEvent() { return positionAtEvent; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
}
