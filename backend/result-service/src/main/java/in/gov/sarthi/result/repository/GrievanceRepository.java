package in.gov.sarthi.result.repository;

import in.gov.sarthi.result.model.Grievance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GrievanceRepository extends JpaRepository<Grievance, Long> {
    Optional<Grievance> findByTicketRef(String ticketRef);
    List<Grievance> findByRollNumberOrderByCreatedAtDesc(String rollNumber);
    List<Grievance> findByStatusOrderByCreatedAtAsc(String status);

    /** Used by DataRetentionScheduler — only closed tickets past the retention window are ever eligible. */
    List<Grievance> findByStatusInAndUpdatedAtBefore(List<String> statuses, Instant cutoff);
}
