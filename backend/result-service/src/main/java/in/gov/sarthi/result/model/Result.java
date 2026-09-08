package in.gov.sarthi.result.model;

import in.gov.sarthi.result.security.FreeTextEncryptedConverter;
import in.gov.sarthi.result.security.SearchableEncryptedConverter;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "results", indexes = {
        @Index(name = "idx_results_roll_number", columnList = "rollNumber", unique = true)
})
public class Result {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Deterministic encryption: findByRollNumber and the unique index both
    // still work (same plaintext -> same ciphertext), but a raw DB dump no
    // longer hands out a plaintext roll number. See PiiEncryptionService's
    // class comment for the ECB/equality-leak tradeoff this makes — the
    // same tradeoff already accepted for Grievance.rollNumber.
    @Convert(converter = SearchableEncryptedConverter.class)
    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String rollNumber;

    @Column(nullable = false)
    private String examId;

    // Free-text (non-deterministic) encryption: name is never queried by
    // exact match, so it gets the stronger, semantically-secure converter
    // rather than the searchable one.
    @Convert(converter = FreeTextEncryptedConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(nullable = false)
    private Integer score;

    @Column(nullable = false)
    private String status; // PASS | FAIL

    @Column(nullable = false)
    private Instant declaredAt;

    protected Result() {}

    public Result(String rollNumber, String examId, String name, Integer score, String status, Instant declaredAt) {
        this.rollNumber = rollNumber;
        this.examId = examId;
        this.name = name;
        this.score = score;
        this.status = status;
        this.declaredAt = declaredAt;
    }

    public Long getId() { return id; }
    public String getRollNumber() { return rollNumber; }
    public String getExamId() { return examId; }
    public String getName() { return name; }
    public Integer getScore() { return score; }
    public String getStatus() { return status; }
    public Instant getDeclaredAt() { return declaredAt; }
}
