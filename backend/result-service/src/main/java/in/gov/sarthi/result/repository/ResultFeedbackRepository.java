package in.gov.sarthi.result.repository;

import in.gov.sarthi.result.model.ResultFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ResultFeedbackRepository extends JpaRepository<ResultFeedback, Long> {
    List<ResultFeedback> findBySubmittedAtBefore(Instant cutoff);
}
