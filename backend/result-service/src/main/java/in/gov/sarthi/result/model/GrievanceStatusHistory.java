package in.gov.sarthi.result.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One row per status transition on a Grievance — who (by IP, since
 * admin identity is a shared key not per-user accounts) changed what,
 * and when. Kept separate from Grievance itself so the ticket's current
 * state stays simple to read while the full history remains available
 * for accountability/review.
 */
@Entity
@Table(name = "grievance_status_history")
public class GrievanceStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long grievanceId;

    @Column(nullable = false)
    private String previousStatus;

    @Column(nullable = false)
    private String newStatus;

    private String adminNote;
    private String changedByIp;

    @Column(nullable = false)
    private Instant changedAt;

    protected GrievanceStatusHistory() {}

    public GrievanceStatusHistory(Long grievanceId, String previousStatus, String newStatus, String adminNote, String changedByIp) {
        this.grievanceId = grievanceId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.adminNote = adminNote;
        this.changedByIp = changedByIp;
        this.changedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getGrievanceId() { return grievanceId; }
    public String getPreviousStatus() { return previousStatus; }
    public String getNewStatus() { return newStatus; }
    public String getAdminNote() { return adminNote; }
    public String getChangedByIp() { return changedByIp; }
    public Instant getChangedAt() { return changedAt; }
}
