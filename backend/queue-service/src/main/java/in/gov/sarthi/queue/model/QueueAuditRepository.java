package in.gov.sarthi.queue.model;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface QueueAuditRepository extends MongoRepository<QueueAuditEvent, String> {
}
