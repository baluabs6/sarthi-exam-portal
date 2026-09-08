package in.gov.sarthi.result.model;

import in.gov.sarthi.result.security.SearchableEncryptedConverter;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One row per stage transition for a candidate's application —
 * SUBMITTED → VERIFIED → ADMIT_CARD_ISSUED → RESULT_DECLARED — instead
 * of only ever exposing a single point-in-time result lookup.
 *
 * rollNumber is deterministically encrypted (SearchableEncryptedConverter),
 * consistent with Grievance and (as of the results-table encryption fix)
 * Result itself — a raw DB dump of this table no longer hands out a
 * plaintext roll number either. stage/note carry no identity information
 * on their own, so they're left as-is.
 */
@Entity
@Table(name = "application_status_history", indexes = {
        @Index(name = "idx_app_status_roll_exam_lookup", columnList = "rollNumber, examId")
})
public class ApplicationStatusHistory {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String VERIFIED = "VERIFIED";
    public static final String ADMIT_CARD_ISSUED = "ADMIT_CARD_ISSUED";
    public static final String RESULT_DECLARED = "RESULT_DECLARED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = SearchableEncryptedConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String rollNumber;

    @Column(nullable = false)
    private String examId;

    @Column(nullable = false)
    private String stage;

    private String note;

    @Column(nullable = false)
    private Instant changedAt;

    protected ApplicationStatusHistory() {}

    public ApplicationStatusHistory(String rollNumber, String examId, String stage, String note) {
        this.rollNumber = rollNumber;
        this.examId = examId;
        this.stage = stage;
        this.note = note;
        this.changedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRollNumber() { return rollNumber; }
    public String getExamId() { return examId; }
    public String getStage() { return stage; }
    public String getNote() { return note; }
    public Instant getChangedAt() { return changedAt; }
}
