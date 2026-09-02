package in.gov.sarthi.result.repository;

import in.gov.sarthi.result.model.GrievanceStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GrievanceStatusHistoryRepository extends JpaRepository<GrievanceStatusHistory, Long> {
    List<GrievanceStatusHistory> findByGrievanceIdIn(List<Long> grievanceIds);
}
