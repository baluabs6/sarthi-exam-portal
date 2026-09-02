package in.gov.sarthi.queue.model;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AdminAuditRepository extends MongoRepository<AdminAuditEvent, String> {
    List<AdminAuditEvent> findTop50ByOrderByTimestampDesc();
}
