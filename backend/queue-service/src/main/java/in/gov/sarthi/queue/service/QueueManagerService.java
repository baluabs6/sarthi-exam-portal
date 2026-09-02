package in.gov.sarthi.queue.service;

import in.gov.sarthi.queue.dto.JoinResponse;
import in.gov.sarthi.queue.dto.QueueSnapshot;
import in.gov.sarthi.queue.model.QueueAuditEvent;
import in.gov.sarthi.queue.model.QueueAuditRepository;
import in.gov.sarthi.queue.security.CaptchaService;
import in.gov.sarthi.queue.security.TicketService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Implements the "virtual waiting room" pattern:
 *
 *  1. Each joiner gets a monotonically increasing score in a Redis sorted
 *     set keyed by exam ("queue:{examId}") — this gives FIFO ordering
 *     that's shared correctly across every queue-service replica, which
 *     is exactly the property a single in-memory queue can't give you
 *     once you auto-scale to multiple pods.
 *  2. Position is just ZRANK — O(log n), cheap even with millions
 *     waiting.
 *  3. A scheduler (see QueueAdmissionScheduler) admits a controlled
 *     number of people per tick, which is what protects result-service
 *     and its database from ever seeing more concurrent load than they
 *     were provisioned for — the crash is converted into a wait.
 *  4. Redis persistence (AOF/RDB, configured at the infra level) means a
 *     queue-service restart does not lose anyone's place in line.
 *
 * Security additions:
 *  - A CAPTCHA challenge must be solved before a slot is created, to
 *    raise the cost of scripted mass-joining.
 *  - The moment a user is admitted, a signed ticket (TicketService) is
 *    minted and bound to their exact rollNumber/examId — this is what
 *    result-service now actually verifies, closing the previous gap
 *    where /api/results/{rollNumber} could be called directly.
 *  - Every audit event is hash-chained so the Mongo audit trail is
 *    tamper-evident.
 */
@Service
public class QueueManagerService {

    private final StringRedisTemplate redis;
    private final QueueAuditRepository auditRepository;
    private final CaptchaService captchaService;
    private final TicketService ticketService;

    @Value("${queue.admit-per-tick:5}")
    private int configuredAdmitPerTick;
    private java.util.concurrent.atomic.AtomicInteger admitPerTick;

    @jakarta.annotation.PostConstruct
    void init() {
        this.admitPerTick = new java.util.concurrent.atomic.AtomicInteger(configuredAdmitPerTick);
    }

    @Value("${queue.tick-interval-seconds:2}")
    private int tickIntervalSeconds;

    public QueueManagerService(StringRedisTemplate redis,
                                QueueAuditRepository auditRepository,
                                CaptchaService captchaService,
                                TicketService ticketService) {
        this.redis = redis;
        this.auditRepository = auditRepository;
        this.captchaService = captchaService;
        this.ticketService = ticketService;
    }

    private String queueKey(String examId) {
        return "queue:" + examId;
    }

    private String metaKey(String queueId) {
        return "queue:meta:" + queueId;
    }

    private String seqKey(String examId) {
        return "queue:seq:" + examId;
    }

    private String auditChainKey(String examId) {
        return "queue:audit:lasthash:" + examId;
    }

    public static class InvalidCaptchaException extends RuntimeException {
        public InvalidCaptchaException() {
            super("Verification challenge answer is incorrect or expired.");
        }
    }

    public JoinResponse join(String examId, String rollNumber, String captchaId, String captchaAnswer, String joiningIp) {
        if (!captchaService.verify(captchaId, captchaAnswer, joiningIp)) {
            throw new InvalidCaptchaException();
        }

        recordJoinTimestamp(examId);
        recordRollNumberAttempt(joiningIp, rollNumber);

        String queueId = UUID.randomUUID().toString();
        long sequence = redis.opsForValue().increment(seqKey(examId));

        redis.opsForZSet().add(queueKey(examId), queueId, sequence);
        redis.opsForHash().putAll(metaKey(queueId), Map.of(
                "examId", examId,
                "rollNumber", rollNumber,
                "joiningIp", joiningIp == null ? "" : joiningIp,
                "lastSeenAt", String.valueOf(System.currentTimeMillis())
        ));
        redis.expire(metaKey(queueId), Duration.ofHours(6));

        Long rank = redis.opsForZSet().rank(queueKey(examId), queueId);
        int position = (int) (rank == null ? 0 : rank) + 1;
        long waitSeconds = estimateWaitSeconds(position);

        recordAuditEvent(new QueueAuditEvent(queueId, examId, rollNumber, "JOINED", position));

        return new JoinResponse(queueId, position, waitSeconds);
    }

    /**
     * Called by {@code QueueAdmissionScheduler} the instant it pops a user
     * off the Redis queue. Mints the same signed ticket used by the
     * polling path, and records the ADMITTED event on the tamper-evident
     * audit chain, so both delivery paths (live WebSocket push and REST
     * poll fallback) hand out a real, verifiable ticket rather than the
     * bare queueId. The ticket is soft-bound to the IP that originally
     * joined the queue (not necessarily the one redeeming it later) —
     * see result-service's TicketService for how that's checked.
     */
    public QueueSnapshot admitNow(String examId, String queueId, String rollNumber, String joiningIp) {
        String ticket = ticketService.issue(examId, rollNumber, queueId, joiningIp);
        recordAuditEvent(new QueueAuditEvent(queueId, examId, rollNumber, "ADMITTED", 0));
        return QueueSnapshot.admittedNow(queueId, ticket);
    }

    public QueueSnapshot snapshot(String queueId) {
        Map<Object, Object> meta = redis.opsForHash().entries(metaKey(queueId));
        if (meta.isEmpty()) {
            // No longer in Redis at all — either never existed, or the
            // TTL expired. We can't safely mint a ticket here since we no
            // longer know the rollNumber/examId this queueId belonged to,
            // so treat this as "nothing to admit" rather than granting
            // access.
            return QueueSnapshot.admittedNow(queueId, null);
        }
        String examId = (String) meta.get("examId");
        String rollNumber = (String) meta.get("rollNumber");
        String joiningIp = (String) meta.get("joiningIp");
        Long rank = redis.opsForZSet().rank(queueKey(examId), queueId);

        if (rank == null) {
            // Removed from the sorted set by the admission scheduler =
            // it's this user's turn (the scheduler normally admits via
            // admitNow() already, but a poll can race and land here
            // first — admitNow() is safe to call again since it simply
            // (re)issues a fresh signed ticket).
            return admitNow(examId, queueId, rollNumber, joiningIp);
        }

        int position = rank.intValue() + 1;
        int aheadOfYou = rank.intValue();
        long waitSeconds = estimateWaitSeconds(position);
        String status = loadStatus();

        // Feeds abandonment estimation (see estimateAbandonment) — a
        // waiting user whose browser hasn't checked in for a long while
        // relative to their queue depth is statistically more likely to
        // have closed the tab than one polling/connected recently.
        redis.opsForHash().put(metaKey(queueId), "lastSeenAt", String.valueOf(System.currentTimeMillis()));

        return QueueSnapshot.waiting(queueId, position, aheadOfYou, waitSeconds, status);
    }

    private long estimateWaitSeconds(int position) {
        double effectiveRate = getEffectiveAdmitRate();
        double ticksNeeded = Math.ceil(position / Math.max(1.0, effectiveRate));
        return (long) (ticksNeeded * tickIntervalSeconds);
    }

    private static final String THROUGHPUT_SAMPLES_KEY = "queue:throughput:samples";
    private static final int THROUGHPUT_SAMPLE_WINDOW = 20;

    /**
     * Called by the scheduler after every tick with how many people it
     * actually admitted (which can differ from the configured
     * admitPerTick if fewer people were waiting than the cap). Kept as a
     * capped rolling window in Redis so this stays cheap and works
     * correctly across multiple queue-service replicas.
     */
    public void recordAdmissionSample(int actuallyAdmitted) {
        redis.opsForList().rightPush(THROUGHPUT_SAMPLES_KEY, String.valueOf(actuallyAdmitted));
        redis.opsForList().trim(THROUGHPUT_SAMPLES_KEY, -THROUGHPUT_SAMPLE_WINDOW, -1);
    }

    /**
     * Blends the configured target rate with what's actually been
     * observed recently. This makes the estimated wait time honest about
     * real conditions (e.g. a temporarily degraded downstream service
     * causing the scheduler to admit fewer people than configured)
     * rather than always assuming the textbook rate — without needing
     * any external ML model, just a rolling average of real ticks.
     */
    private double getEffectiveAdmitRate() {
        List<String> samples = redis.opsForList().range(THROUGHPUT_SAMPLES_KEY, 0, -1);
        int configured = admitPerTick.get();
        if (samples == null || samples.size() < 3) {
            return configured; // not enough real data yet — fall back to the configured target
        }
        double sum = 0;
        int count = 0;
        for (String s : samples) {
            try {
                sum += Integer.parseInt(s);
                count++;
            } catch (NumberFormatException ignored) { /* skip malformed sample */ }
        }
        if (count == 0) return configured;
        double observedAvg = sum / count;
        // Never let a couple of unusually quiet ticks make estimates
        // wildly optimistic — floor at half the configured rate.
        return Math.max(observedAvg, configured / 2.0);
    }

    /** Exposed for the admin dashboard, to show observed vs configured throughput side by side. */
    public double getObservedAdmitRate() {
        List<String> samples = redis.opsForList().range(THROUGHPUT_SAMPLES_KEY, 0, -1);
        if (samples == null || samples.isEmpty()) return -1;
        double sum = 0;
        int count = 0;
        for (String s : samples) {
            try { sum += Integer.parseInt(s); count++; } catch (NumberFormatException ignored) {}
        }
        return count == 0 ? -1 : sum / count;
    }

    /** Simple system-load classification, surfaced on the public status page. */
    public String loadStatus() {
        Long depth = totalWaitingAcrossExams();
        if (depth > 5000) return "critical";
        if (depth > 500) return "high-load";
        return "normal";
    }

    /** Aggregated-only total, safe to expose publicly (see AdminController for the per-exam breakdown, which is not public). */
    public long totalWaitingAcrossExams() {
        long depth = 0L;
        for (String key : Set.of("queue:NEET-UG-2026", "queue:JEE-MAIN-2026", "queue:SSC-CGL-2026")) {
            Long size = redis.opsForZSet().size(key);
            depth += size == null ? 0 : size;
        }
        return depth;
    }

    private static final String JOIN_TIMESTAMPS_KEY_PREFIX = "queue:join-timestamps:";

    private static final String ROLL_ATTEMPTS_KEY_PREFIX = "queue:roll-attempts:";
    private static final int ENUMERATION_THRESHOLD = 8; // distinct roll numbers from one IP within the window below

    /**
     * A single IP joining with one roll number, then another, then
     * another, in quick succession looks less like "several people
     * behind one router" and more like a script probing/enumerating
     * roll numbers. This tracks distinct roll numbers per IP and flags
     * anything past a threshold within a short window.
     */
    private void recordRollNumberAttempt(String ip, String rollNumber) {
        if (ip == null) return;
        String key = ROLL_ATTEMPTS_KEY_PREFIX + ip;
        redis.opsForSet().add(key, rollNumber);
        redis.expire(key, Duration.ofMinutes(10));
        redis.opsForSet().add(RECENT_IPS_KEY, ip);
        redis.expire(RECENT_IPS_KEY, Duration.ofMinutes(10));
    }

    private static final String RECENT_IPS_KEY = "queue:recent-ips";

    public boolean isSuspectedEnumeration(String ip) {
        if (ip == null) return false;
        Long distinctCount = redis.opsForSet().size(ROLL_ATTEMPTS_KEY_PREFIX + ip);
        return distinctCount != null && distinctCount >= ENUMERATION_THRESHOLD;
    }

    /** IPs from the last 10 minutes whose distinct-roll-number count crosses the enumeration threshold — a real, checkable list for admins, not just a boolean per lookup. */
    public List<String> suspectedEnumerationIps() {
        Set<String> recentIps = redis.opsForSet().members(RECENT_IPS_KEY);
        if (recentIps == null) return List.of();
        return recentIps.stream().filter(this::isSuspectedEnumeration).toList();
    }

    private static final String FAQ_MISSES_KEY = "faq:misses";

    /** Records an unmatched FAQ search — see QueueController.reportFaqMiss. */
    public void recordFaqMiss(String normalizedQuery) {
        redis.opsForZSet().incrementScore(FAQ_MISSES_KEY, normalizedQuery, 1);
    }

    /** Top unanswered questions, for admins to close real content gaps instead of guessing. */
    public java.util.Map<String, Double> topFaqGaps(int limit) {
        var results = redis.opsForZSet().reverseRangeWithScores(FAQ_MISSES_KEY, 0, limit - 1);
        java.util.Map<String, Double> gaps = new java.util.LinkedHashMap<>();
        if (results != null) {
            results.forEach(tuple -> gaps.put(tuple.getValue(), tuple.getScore()));
        }
        return gaps;
    }

    /**
     * Approximates how many people currently waiting have likely
     * abandoned (closed the tab) without ever being removed from the
     * queue. Uses "time since last REST poll" as the signal — an
     * honestly imperfect proxy: a user on a live WebSocket connection
     * doesn't need to poll at all, so their lastSeenAt can go stale
     * while they're still genuinely present. This will over-count
     * WebSocket-connected users as "possibly abandoned" to some degree;
     * a more complete version would also track WebSocket
     * subscribe/heartbeat events, not just REST polls. Treat this as a
     * rough signal for admin awareness, not a precise measurement.
     */
    public int estimatePossiblyAbandoned(String examId, long staleThresholdMs) {
        var members = redis.opsForZSet().range(queueKey(examId), 0, -1);
        if (members == null || members.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        int staleCount = 0;
        for (String queueId : members) {
            String lastSeenRaw = (String) redis.opsForHash().get(metaKey(queueId), "lastSeenAt");
            if (lastSeenRaw == null) continue;
            long lastSeen = Long.parseLong(lastSeenRaw);
            if (now - lastSeen > staleThresholdMs) staleCount++;
        }
        return staleCount;
    }

    private void recordJoinTimestamp(String examId) {
        String key = JOIN_TIMESTAMPS_KEY_PREFIX + examId;
        redis.opsForList().rightPush(key, String.valueOf(System.currentTimeMillis()));
        redis.opsForList().trim(key, -50, -1); // keep a rolling window, cheap to scan
        redis.expire(key, Duration.ofHours(1));
    }

    /**
     * A simple linear projection, not a trained forecasting model:
     * (recent joins per minute) vs (current admission rate per minute)
     * tells you whether the queue is growing or shrinking, and roughly
     * how fast — enough to warn "this will hit critical load in ~N
     * minutes at the current rate" without needing a real time-series
     * model. Returns null if there isn't enough recent join data yet.
     */
    public Long projectedMinutesToCritical(String examId, long criticalThreshold) {
        List<String> timestamps = redis.opsForList().range(JOIN_TIMESTAMPS_KEY_PREFIX + examId, 0, -1);
        if (timestamps == null || timestamps.size() < 5) return null;

        long now = System.currentTimeMillis();
        long windowStart = now - Duration.ofMinutes(5).toMillis();
        long recentJoins = timestamps.stream()
                .mapToLong(Long::parseLong)
                .filter(t -> t >= windowStart)
                .count();
        if (recentJoins == 0) return null;

        double joinsPerMinute = recentJoins / 5.0;
        double admittedPerMinute = (60.0 / tickIntervalSeconds) * admitPerTick.get();
        double netGrowthPerMinute = joinsPerMinute - admittedPerMinute;

        Long currentDepth = redis.opsForZSet().size(queueKey(examId));
        if (currentDepth == null) return null;

        if (netGrowthPerMinute <= 0 || currentDepth >= criticalThreshold) return null; // already shrinking, or already there
        double minutesToCritical = (criticalThreshold - currentDepth) / netGrowthPerMinute;
        return Math.round(minutesToCritical);
    }

    public int getAdmitPerTick() {
        return admitPerTick.get();
    }

    /** Live admin tuning — see AdminController. Takes effect on the very next scheduler tick. */
    public void setAdmitPerTick(int newValue) {
        if (newValue < 1) {
            throw new IllegalArgumentException("admitPerTick must be at least 1");
        }
        admitPerTick.set(newValue);
    }

    /** Snapshot of live queue depth per exam, for the admin dashboard. */
    public Map<String, Long> queueDepthByExam(java.util.List<String> examIds) {
        Map<String, Long> depths = new java.util.LinkedHashMap<>();
        for (String examId : examIds) {
            Long size = redis.opsForZSet().size(queueKey(examId));
            depths.put(examId, size == null ? 0L : size);
        }
        return depths;
    }

    public String getQueueKeyForExam(String examId) {
        return queueKey(examId);
    }

    /** Appends an event to the tamper-evident, hash-chained audit trail. */
    private void recordAuditEvent(QueueAuditEvent event) {
        String prevHash = redis.opsForValue().get(auditChainKey(event.getExamId()));
        event.setPrevHash(prevHash);
        event.setHash(computeHash(event, prevHash));
        auditRepository.save(event);
        redis.opsForValue().set(auditChainKey(event.getExamId()), event.getHash());
    }

    private String computeHash(QueueAuditEvent event, String prevHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.join("|",
                    String.valueOf(prevHash),
                    event.getQueueId(),
                    event.getExamId(),
                    event.getRollNumber(),
                    event.getEventType(),
                    String.valueOf(event.getPositionAtEvent()),
                    String.valueOf(event.getTimestamp()));
            byte[] hashed = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM; this is unreachable.
            throw new IllegalStateException(e);
        }
    }
}
