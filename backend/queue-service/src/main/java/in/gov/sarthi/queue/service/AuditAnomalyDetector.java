package in.gov.sarthi.queue.service;

import in.gov.sarthi.queue.model.QueueAuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A plain statistical check over the existing hash-chained audit
 * trail — not a trained model — flagging minutes where ADMITTED events
 * spike well outside the recent baseline. Two minutes with 5 and 6
 * admissions and then one with 40 is the kind of pattern worth a human
 * look, whether that turns out to be a legitimate manual admit-rate
 * bump or something worth investigating.
 *
 * Deliberately simple: bucket ADMITTED events into one-minute windows
 * over the last hour, compute the mean and standard deviation, and flag
 * any bucket more than 3 standard deviations above the mean. This is
 * genuinely useful at this data volume without needing a trained
 * anomaly-detection model or any external dependency.
 */
@Service
public class AuditAnomalyDetector {

    private final MongoTemplate mongoTemplate;

    @Value("${anomaly.stddev-threshold:3.0}")
    private double stdDevThreshold;

    public AuditAnomalyDetector(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public record AnomalyWindow(String minuteStart, int admittedCount, double baselineMean, double baselineStdDev) {}

    public List<AnomalyWindow> detectAdmissionBursts(String examId) {
        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        Query query = new Query(Criteria.where("examId").is(examId)
                .and("eventType").is("ADMITTED")
                .and("timestamp").gte(since));
        List<QueueAuditEvent> events = mongoTemplate.find(query, QueueAuditEvent.class);

        if (events.size() < 10) return List.of(); // not enough data for a meaningful baseline

        // Bucket into one-minute windows.
        Map<Long, Integer> countsByMinute = new TreeMap<>();
        for (QueueAuditEvent event : events) {
            long minuteBucket = event.getTimestamp().getEpochSecond() / 60;
            countsByMinute.merge(minuteBucket, 1, Integer::sum);
        }

        double mean = countsByMinute.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = countsByMinute.values().stream()
                .mapToDouble(c -> Math.pow(c - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);

        List<AnomalyWindow> anomalies = new ArrayList<>();
        if (stdDev == 0) return anomalies; // perfectly uniform — nothing to flag

        for (Map.Entry<Long, Integer> entry : countsByMinute.entrySet()) {
            if (entry.getValue() > mean + (stdDevThreshold * stdDev)) {
                Instant minuteStart = Instant.ofEpochSecond(entry.getKey() * 60);
                anomalies.add(new AnomalyWindow(minuteStart.toString(), entry.getValue(), mean, stdDev));
            }
        }
        return anomalies;
    }
}
