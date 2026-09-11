package in.gov.sarthi.result.model;

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

    @Column(nullable = false, unique = true)
    private String rollNumber;

    @Column(nullable = false)
    private String examId;

    @Column(nullable = false)
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
