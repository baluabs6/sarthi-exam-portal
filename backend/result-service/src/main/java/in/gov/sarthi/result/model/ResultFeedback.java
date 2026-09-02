package in.gov.sarthi.result.model;

import in.gov.sarthi.result.security.FreeTextEncryptedConverter;
import in.gov.sarthi.result.security.SearchableEncryptedConverter;
import jakarta.persistence.*;

import java.time.Instant;

/** A quick thumbs-up/down + optional comment after viewing a result, to feed portal improvements. */
@Entity
@Table(name = "result_feedback")
public class ResultFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = SearchableEncryptedConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String rollNumber;

    @Column(nullable = false)
    private boolean helpful;

    @Convert(converter = FreeTextEncryptedConverter.class)
    @Column(columnDefinition = "TEXT")
    private String comment;

    /**
     * Rule-based urgency score (0-3), computed at submission time from a
     * fixed keyword list in the comment — see FeedbackController. Not an
     * ML model; stored on the plaintext-adjacent side of the entity
     * deliberately so admins can sort/filter by it without decrypting
     * every comment first.
     */
    @Column(nullable = false)
    private int urgencyScore;

    @Column(nullable = false)
    private Instant submittedAt;

    protected ResultFeedback() {}

    public ResultFeedback(String rollNumber, boolean helpful, String comment, int urgencyScore) {
        this.rollNumber = rollNumber;
        this.helpful = helpful;
        this.comment = comment;
        this.urgencyScore = urgencyScore;
        this.submittedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRollNumber() { return rollNumber; }
    public boolean isHelpful() { return helpful; }
    public String getComment() { return comment; }
    public int getUrgencyScore() { return urgencyScore; }
    public Instant getSubmittedAt() { return submittedAt; }
}
