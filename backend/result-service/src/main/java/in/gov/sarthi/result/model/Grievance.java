package in.gov.sarthi.result.model;

import in.gov.sarthi.result.security.FreeTextEncryptedConverter;
import in.gov.sarthi.result.security.SearchableEncryptedConverter;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * A candidate's formal objection/query about a declared result (e.g.
 * "my marks look wrong", "name is misspelled on the certificate").
 * Distinct from ad-hoc support contact — this is tracked, has a status
 * lifecycle, and gives the candidate a reference number to follow up
 * with.
 *
 * rollNumber and message are encrypted at rest (see the two converter
 * classes) — a raw database dump or backup is not plaintext PII by
 * itself. rollNumber uses the searchable (deterministic) converter so
 * lookups by roll number keep working; message uses the randomized one
 * since it's never queried by exact match.
 */
@Entity
@Table(name = "grievances", indexes = {
        @Index(name = "idx_grievances_roll_number_lookup", columnList = "rollNumber")
})
public class Grievance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private String ticketRef;

    @Convert(converter = SearchableEncryptedConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String rollNumber;

    @Column(nullable = false)
    private String examId;

    @Column(nullable = false)
    private String category; // MARKS_DISCREPANCY | NAME_CORRECTION | CERTIFICATE_ISSUE | OTHER

    @Convert(converter = FreeTextEncryptedConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false)
    private String status; // RAISED | UNDER_REVIEW | RESOLVED | REJECTED

    /** Rough script-based guess (see LanguageHintDetector) — routes non-English submissions to staff who can read them. */
    @Column(nullable = false, length = 8)
    private String languageHint;

    @Column(columnDefinition = "TEXT")
    private String adminNote;

    /** Optional candidate-supplied callback — see WebhookNotifier for the SSRF checks applied before ever using it. */
    @Column(columnDefinition = "TEXT")
    private String webhookUrl;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Grievance() {}

    public Grievance(String ticketRef, String rollNumber, String examId, String category, String message, String languageHint, String webhookUrl) {
        this.ticketRef = ticketRef;
        this.rollNumber = rollNumber;
        this.examId = examId;
        this.category = category;
        this.message = message;
        this.languageHint = languageHint;
        this.webhookUrl = webhookUrl;
        this.status = "RAISED";
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateStatus(String status, String adminNote) {
        this.status = status;
        this.adminNote = adminNote;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTicketRef() { return ticketRef; }
    public String getRollNumber() { return rollNumber; }
    public String getExamId() { return examId; }
    public String getCategory() { return category; }
    public String getMessage() { return message; }
    public String getStatus() { return status; }
    public String getLanguageHint() { return languageHint; }
    public String getAdminNote() { return adminNote; }
    public String getWebhookUrl() { return webhookUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
