package in.gov.sarthi.result.service;

import in.gov.sarthi.result.dto.GrievanceRequest;
import in.gov.sarthi.result.dto.GrievanceResponse;
import in.gov.sarthi.result.dto.GrievanceStatusUpdateRequest;
import in.gov.sarthi.result.model.Grievance;
import in.gov.sarthi.result.model.GrievanceStatusHistory;
import in.gov.sarthi.result.repository.GrievanceRepository;
import in.gov.sarthi.result.repository.GrievanceStatusHistoryRepository;
import in.gov.sarthi.result.util.LanguageHintDetector;
import in.gov.sarthi.result.util.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gives candidates a structured way to formally raise a query about
 * their result (wrong marks, name spelling, certificate issue) instead
 * of having no recourse at all. Each grievance gets a reference number
 * the candidate can use to check status later without needing to log in
 * to anything. Every status change is recorded in
 * GrievanceStatusHistory so — since admin access here is a shared key,
 * not per-user accounts — there is still a reviewable trail of who (by
 * IP) changed what and when.
 */
@Service
public class GrievanceService {

    private static final Logger log = LoggerFactory.getLogger(GrievanceService.class);

    // The gateway already rate-limits /api/grievances/** per IP, but
    // that alone doesn't stop one IP spamming many different roll
    // numbers, or repeatedly targeting the same one. This adds a second,
    // narrower limit keyed on rollNumber itself.
    private static final int MAX_GRIEVANCES_PER_ROLL_PER_HOUR = 5;

    // Jaccard word-overlap threshold above which a new grievance is
    // considered a near-duplicate of an existing open one from the same
    // roll number. This is a simple set-overlap heuristic, not a trained
    // similarity model — genuinely useful for catching "I already said
    // this" resubmissions without needing embeddings or an API key.
    private static final double DUPLICATE_SIMILARITY_THRESHOLD = 0.6;

    private final GrievanceRepository repository;
    private final GrievanceStatusHistoryRepository historyRepository;
    private final StringRedisTemplate redis;
    private final WebhookNotifier webhookNotifier;

    public GrievanceService(GrievanceRepository repository, GrievanceStatusHistoryRepository historyRepository,
                             StringRedisTemplate redis, WebhookNotifier webhookNotifier) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.redis = redis;
        this.webhookNotifier = webhookNotifier;
    }

    public static class GrievanceNotFoundException extends RuntimeException {
        public GrievanceNotFoundException(String ticketRef) {
            super("No grievance found for ticket reference: " + ticketRef);
        }
    }

    public static class TooManyGrievancesException extends RuntimeException {
        public TooManyGrievancesException() {
            super("Too many grievances raised for this roll number recently. Please try again later.");
        }
    }

    public static class InvalidWebhookUrlException extends RuntimeException {
        public InvalidWebhookUrlException() {
            super("The webhook URL provided isn't allowed — it must be a public http(s) address, not an internal/private one.");
        }
    }

    public GrievanceResponse raise(GrievanceRequest request, String idempotencyKey) {
        // Idempotency: a double-tap or a retried request with the same
        // client-supplied key returns the original ticket instead of
        // silently creating a duplicate.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existingTicketRef = redis.opsForValue().get(idempotencyKey(idempotencyKey));
            if (existingTicketRef != null) {
                return getByTicketRef(existingTicketRef);
            }
        }

        String rateLimitKey = "grievance:rate:" + request.rollNumber();
        Long count = redis.opsForValue().increment(rateLimitKey);
        if (count != null && count == 1) {
            redis.expire(rateLimitKey, Duration.ofHours(1));
        }
        if (count != null && count > MAX_GRIEVANCES_PER_ROLL_PER_HOUR) {
            throw new TooManyGrievancesException();
        }

        // Near-duplicate check: if this candidate already has a
        // still-open grievance whose text substantially overlaps with
        // this one, return that existing ticket rather than opening a
        // second copy of the same issue.
        Optional<Grievance> duplicate = findOpenDuplicate(request.rollNumber(), request.message());
        if (duplicate.isPresent()) {
            log.info("Near-duplicate grievance detected for {} — returning existing {}",
                    PiiMasker.maskRollNumber(request.rollNumber()), duplicate.get().getTicketRef());
            return GrievanceResponse.from(duplicate.get());
        }

        String ticketRef = "GRV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String languageHint = LanguageHintDetector.detect(request.message());
        String webhookUrl = request.webhookUrl();
        if (webhookUrl != null && !webhookUrl.isBlank() && !webhookNotifier.isSafeUrl(webhookUrl)) {
            // Fail clearly at submission time rather than silently
            // dropping an unsafe URL later when the webhook would have fired.
            throw new InvalidWebhookUrlException();
        }
        Grievance saved = repository.save(new Grievance(
                ticketRef, request.rollNumber(), request.examId(), request.category(), request.message(),
                languageHint, webhookUrl));
        log.info("Grievance {} raised for {} (lang={})", ticketRef, PiiMasker.maskRollNumber(request.rollNumber()), languageHint);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            redis.opsForValue().set(idempotencyKey(idempotencyKey), ticketRef, Duration.ofHours(24));
        }

        return GrievanceResponse.from(saved);
    }

    private Optional<Grievance> findOpenDuplicate(String rollNumber, String newMessage) {
        Set<String> newWords = wordSet(newMessage);
        if (newWords.isEmpty()) return Optional.empty();

        return repository.findByRollNumberOrderByCreatedAtDesc(rollNumber).stream()
                .filter(g -> g.getStatus().equals("RAISED") || g.getStatus().equals("UNDER_REVIEW"))
                .filter(g -> jaccardSimilarity(newWords, wordSet(g.getMessage())) >= DUPLICATE_SIMILARITY_THRESHOLD)
                .findFirst();
    }

    private Set<String> wordSet(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(text.toLowerCase().split("\\W+")));
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private String idempotencyKey(String key) {
        return "grievance:idempotency:" + key;
    }

    public GrievanceResponse getByTicketRef(String ticketRef) {
        Grievance g = repository.findByTicketRef(ticketRef)
                .orElseThrow(() -> new GrievanceNotFoundException(ticketRef));
        boolean isOpen = g.getStatus().equals("RAISED") || g.getStatus().equals("UNDER_REVIEW");
        Double estimate = isOpen ? estimateResolutionDays(g.getCategory()) : null;
        return GrievanceResponse.from(g, estimate);
    }

    /**
     * A real statistic from GrievanceStatusHistory — average days between
     * a ticket being raised and its most recent resolution in the same
     * category — not a guess or a fixed constant. Returns null if there
     * isn't enough resolved history in this category yet to say anything
     * meaningful.
     */
    private Double estimateResolutionDays(String category) {
        List<Grievance> resolved = repository.findByStatusOrderByCreatedAtAsc("RESOLVED").stream()
                .filter(g -> g.getCategory().equals(category))
                .toList();
        if (resolved.size() < 3) return null;

        double avgSeconds = resolved.stream()
                .mapToLong(g -> java.time.Duration.between(g.getCreatedAt(), g.getUpdatedAt()).getSeconds())
                .average()
                .orElse(0);
        return Math.round((avgSeconds / 86400.0) * 10) / 10.0; // days, rounded to 1 decimal
    }

    public List<GrievanceResponse> listByStatus(String status) {
        return repository.findByStatusOrderByCreatedAtAsc(status).stream()
                .map(GrievanceResponse::from)
                .toList();
    }

    /** Self-serve export of a candidate's own grievance history. */
    public List<GrievanceResponse> listForRollNumber(String rollNumber) {
        return repository.findByRollNumberOrderByCreatedAtDesc(rollNumber).stream()
                .map(GrievanceResponse::from)
                .toList();
    }

    /**
     * Rule-based "draft suggestion": the most recent admin note used to
     * resolve a grievance in the same category, offered as an editable
     * starting point. Not an LLM call — just "here's what worked last
     * time for this category" pulled from real history.
     */
    public Optional<String> suggestDraftNote(String category) {
        return repository.findByStatusOrderByCreatedAtAsc("RESOLVED").stream()
                .filter(g -> g.getCategory().equals(category))
                .filter(g -> g.getAdminNote() != null && !g.getAdminNote().isBlank())
                .reduce((first, second) -> second) // most recent (list is ascending by createdAt)
                .map(Grievance::getAdminNote);
    }

    public GrievanceResponse updateStatus(String ticketRef, GrievanceStatusUpdateRequest request, String changedByIp) {
        Grievance g = repository.findByTicketRef(ticketRef)
                .orElseThrow(() -> new GrievanceNotFoundException(ticketRef));
        String previousStatus = g.getStatus();
        g.updateStatus(request.status(), request.adminNote());
        Grievance saved = repository.save(g);

        historyRepository.save(new GrievanceStatusHistory(
                saved.getId(), previousStatus, request.status(), request.adminNote(), changedByIp));
        log.info("Grievance {} status {} -> {}", ticketRef, previousStatus, request.status());

        if (saved.getWebhookUrl() != null && !saved.getWebhookUrl().isBlank()) {
            webhookNotifier.notifyAsync(saved.getWebhookUrl(), saved.getTicketRef(), request.status());
        }

        return GrievanceResponse.from(saved);
    }

    /** Bulk resolve — see AdminController's bulk-actions endpoint. */
    public List<GrievanceResponse> bulkUpdateStatus(List<String> ticketRefs, GrievanceStatusUpdateRequest request, String changedByIp) {
        return ticketRefs.stream()
                .map(ref -> updateStatus(ref, request, changedByIp))
                .toList();
    }

    /**
     * Groups currently-open grievances (across ALL roll numbers, unlike
     * the per-candidate duplicate check above) into clusters of
     * similar-sounding text — a stronger systemic-issue signal than
     * "many tickets share a category" alone, since it catches shared
     * wording even across different categories. Still the same
     * word-overlap heuristic, not embeddings — genuinely useful at this
     * data volume without needing a vector store or a model.
     */
    public record IssueCluster(List<String> ticketRefs, String sampleMessage, int size) {}

    public List<IssueCluster> clusterOpenGrievances() {
        List<Grievance> open = java.util.stream.Stream.of("RAISED", "UNDER_REVIEW")
                .flatMap(s -> repository.findByStatusOrderByCreatedAtAsc(s).stream())
                .toList();

        List<List<Grievance>> clusters = new java.util.ArrayList<>();
        for (Grievance g : open) {
            Set<String> gWords = wordSet(g.getMessage());
            List<Grievance> matchedCluster = clusters.stream()
                    .filter(cluster -> jaccardSimilarity(gWords, wordSet(cluster.get(0).getMessage())) >= DUPLICATE_SIMILARITY_THRESHOLD)
                    .findFirst()
                    .orElse(null);
            if (matchedCluster != null) {
                matchedCluster.add(g);
            } else {
                List<Grievance> newCluster = new java.util.ArrayList<>();
                newCluster.add(g);
                clusters.add(newCluster);
            }
        }

        return clusters.stream()
                .filter(cluster -> cluster.size() >= 3) // only surface genuinely repeated patterns
                .map(cluster -> new IssueCluster(
                        cluster.stream().map(Grievance::getTicketRef).toList(),
                        cluster.get(0).getMessage(),
                        cluster.size()))
                .sorted((a, b) -> b.size() - a.size())
                .toList();
    }

    /**
     * Lightweight, non-LLM search over grievance text — substring/keyword
     * scoring across currently-loaded categories rather than an
     * embeddings-based semantic search (which would need a vector store
     * + a real embedding model). Good enough for "find anything
     * mentioning 'certificate spelling'" at this data volume.
     */
    public List<GrievanceResponse> search(String query, String status) {
        String needle = query == null ? "" : query.toLowerCase().trim();
        if (needle.isBlank()) return listByStatus(status);

        return repository.findByStatusOrderByCreatedAtAsc(status).stream()
                .filter(g -> g.getMessage().toLowerCase().contains(needle)
                        || g.getCategory().toLowerCase().contains(needle)
                        || g.getTicketRef().toLowerCase().contains(needle))
                .map(GrievanceResponse::from)
                .toList();
    }
}
