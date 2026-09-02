package in.gov.sarthi.result.repository;

import in.gov.sarthi.result.model.Result;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResultRepository extends JpaRepository<Result, Long> {
    Optional<Result> findByRollNumber(String rollNumber);
}
