package in.gov.sarthi.result.repository;

import in.gov.sarthi.result.model.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {
    List<ApplicationStatusHistory> findByRollNumberAndExamIdOrderByChangedAtAsc(String rollNumber, String examId);
}
