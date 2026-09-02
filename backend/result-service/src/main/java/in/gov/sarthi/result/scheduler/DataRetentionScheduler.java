package in.gov.sarthi.result.scheduler;

import in.gov.sarthi.result.model.Grievance;
import in.gov.sarthi.result.model.GrievanceStatusHistory;
import in.gov.sarthi.result.model.ResultFeedback;
import in.gov.sarthi.result.repository.GrievanceRepository;
import in.gov.sarthi.result.repository.GrievanceStatusHistoryRepository;
import in.gov.sarthi.result.repository.ResultFeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Closes a real policy gap that was flagged earlier but never enforced:
 * there was no automatic purge for old grievances or feedback, meaning
 * PII (even encrypted) would be retained indefinitely by default. This
 * job deletes resolved/rejected grievances (and their status history)
 * and feedback rows older than a configured retention window, once a
 * day by default.
 *
 * Deliberately conservative: only RESOLVED/REJECTED grievances are
 * purged — anything still RAISED or UNDER_REVIEW is never auto-deleted
 * no matter its age, since an open issue should never silently vanish.
 */
@Component
public class DataRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);
    private static final List<String> PURGEABLE_STATUSES = List.of("RESOLVED", "REJECTED");

    @Value("${retention.grievance-days:365}")
    private int grievanceRetentionDays;

    @Value("${retention.feedback-days:180}")
    private int feedbackRetentionDays;

    private final GrievanceRepository grievanceRepository;
    private final GrievanceStatusHistoryRepository historyRepository;
    private final ResultFeedbackRepository feedbackRepository;

    public DataRetentionScheduler(GrievanceRepository grievanceRepository,
                                   GrievanceStatusHistoryRepository historyRepository,
                                   ResultFeedbackRepository feedbackRepository) {
        this.grievanceRepository = grievanceRepository;
        this.historyRepository = historyRepository;
        this.feedbackRepository = feedbackRepository;
    }

    @Scheduled(cron = "${retention.purge-cron:0 30 3 * * *}") // 03:30 daily by default — low-traffic hours
    @Transactional
    public void purgeExpiredRecords() {
        purgeExpiredGrievances();
        purgeExpiredFeedback();
    }

    private void purgeExpiredGrievances() {
        Instant cutoff = Instant.now().minus(grievanceRetentionDays, ChronoUnit.DAYS);
        List<Grievance> expired = grievanceRepository.findByStatusInAndUpdatedAtBefore(PURGEABLE_STATUSES, cutoff);
        if (expired.isEmpty()) return;

        List<Long> expiredIds = expired.stream().map(Grievance::getId).toList();
        List<GrievanceStatusHistory> history = historyRepository.findByGrievanceIdIn(expiredIds);
        historyRepository.deleteAll(history);
        grievanceRepository.deleteAll(expired);

        log.info("Data retention: purged {} resolved/rejected grievances (and {} history rows) older than {} days",
                expired.size(), history.size(), grievanceRetentionDays);
    }

    private void purgeExpiredFeedback() {
        Instant cutoff = Instant.now().minus(feedbackRetentionDays, ChronoUnit.DAYS);
        List<ResultFeedback> expired = feedbackRepository.findBySubmittedAtBefore(cutoff);
        if (expired.isEmpty()) return;

        feedbackRepository.deleteAll(expired);
        log.info("Data retention: purged {} feedback rows older than {} days", expired.size(), feedbackRetentionDays);
    }
}
