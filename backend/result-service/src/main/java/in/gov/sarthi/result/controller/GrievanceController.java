package in.gov.sarthi.result.controller;

import in.gov.sarthi.result.dto.GrievanceRequest;
import in.gov.sarthi.result.dto.GrievanceResponse;
import in.gov.sarthi.result.dto.GrievanceStatusUpdateRequest;
import in.gov.sarthi.result.security.TicketReplayGuard;
import in.gov.sarthi.result.security.TicketService;
import in.gov.sarthi.result.service.GrievanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/grievances")
public class GrievanceController {

    private static final Logger log = LoggerFactory.getLogger(GrievanceController.class);

    private final GrievanceService grievanceService;
    private final TicketService ticketService;
    private final TicketReplayGuard replayGuard;

    public GrievanceController(GrievanceService grievanceService, TicketService ticketService, TicketReplayGuard replayGuard) {
        this.grievanceService = grievanceService;
        this.ticketService = ticketService;
        this.replayGuard = replayGuard;
    }

    /** Anyone can raise a grievance — no admission ticket required, since a result was already seen. */
    @PostMapping
    public ResponseEntity<GrievanceResponse> raise(
            @Valid @RequestBody GrievanceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(grievanceService.raise(request, idempotencyKey));
    }

    /** Candidates check status with just the reference number they were given — no login needed. This is fine as-is: a GRV- reference has ~32 bits of random entropy, unlike a roll number, so it isn't practically guessable/enumerable. */
    @GetMapping("/{ticketRef}")
    public ResponseEntity<GrievanceResponse> get(@PathVariable String ticketRef) {
        return ResponseEntity.ok(grievanceService.getByTicketRef(ticketRef));
    }

    /**
     * Candidate data export: their own grievance history for a roll
     * number. Previously this only required knowing the roll number
     * itself — since roll numbers are often sequential/guessable (see
     * the seed data), that let anyone pull anyone else's grievance
     * history. Now it requires the same signed admission ticket used for
     * /api/results/{rollNumber}, redeemed a second time under a distinct
     * "grievance-export" resource tag (see TicketReplayGuard — the same
     * genuine queue turn legitimately covers both the result and this
     * export, but each ticket still only works once per resource).
     */
    @GetMapping("/export")
    public ResponseEntity<?> exportForRollNumber(
            @RequestParam String rollNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        String ticket = extractBearerToken(authorization);
        var verified = ticketService.verify(ticket);
        if (verified.isEmpty() || !verified.get().rollNumber().equals(rollNumber)) {
            log.warn("Grievance export denied for a roll number — invalid/mismatched ticket");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "A valid admission ticket is required. Please look up this result first."
            ));
        }
        if (!replayGuard.tryConsume(verified.get().jti(), Duration.ofMinutes(15), "grievance-export")) {
            log.warn("Grievance export denied — ticket already used for this resource");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "This admission ticket has already been used for a grievance export. Please look up the result again."
            ));
        }

        return ResponseEntity.ok(grievanceService.listForRollNumber(rollNumber));
    }

    /** Admin-only: gated by AdminAuthInterceptor via WebConfig on /api/grievances/admin/**. */
    @GetMapping("/admin")
    public ResponseEntity<List<GrievanceResponse>> listForAdmin(
            @RequestParam(defaultValue = "RAISED") String status,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(q == null || q.isBlank()
                ? grievanceService.listByStatus(status)
                : grievanceService.search(q, status));
    }

    /** Rule-based draft suggestion pulled from the most recent resolved ticket in the same category — see GrievanceService.suggestDraftNote. */
    @GetMapping("/admin/draft-suggestion")
    public ResponseEntity<Map<String, String>> draftSuggestion(@RequestParam String category) {
        return ResponseEntity.ok(
                grievanceService.suggestDraftNote(category)
                        .map(note -> Map.of("suggestion", note))
                        .orElse(Map.of("suggestion", "")));
    }

    /** Cross-candidate clustering — surfaces systemic issues, not just per-category counts. See GrievanceService.clusterOpenGrievances. */
    @GetMapping("/admin/clusters")
    public ResponseEntity<List<GrievanceService.IssueCluster>> clusters() {
        return ResponseEntity.ok(grievanceService.clusterOpenGrievances());
    }

    /** Admin CSV export for offline review — same admin gate as the JSON listing above. */
    @GetMapping("/admin/export")
    public ResponseEntity<String> exportForAdmin(@RequestParam(defaultValue = "RAISED") String status) {
        List<GrievanceResponse> rows = grievanceService.listByStatus(status);
        StringBuilder csv = new StringBuilder("ticketRef,rollNumber,examId,category,status,createdAt\n");
        for (GrievanceResponse g : rows) {
            csv.append(String.join(",",
                    g.ticketRef(), g.rollNumber(), g.examId(), g.category(), g.status(), String.valueOf(g.createdAt())
            )).append("\n");
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"grievances-" + status + ".csv\"")
                .header("Content-Type", "text/csv")
                .body(csv.toString());
    }

    @PatchMapping("/admin/{ticketRef}")
    public ResponseEntity<GrievanceResponse> updateStatus(
            @PathVariable String ticketRef, @Valid @RequestBody GrievanceStatusUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(grievanceService.updateStatus(ticketRef, request, actor(httpRequest)));
    }

    public record BulkStatusUpdateRequest(List<String> ticketRefs, GrievanceStatusUpdateRequest update) {}

    /** Admin bulk action: resolve/reject many tickets in one call instead of one-by-one. */
    @PatchMapping("/admin/bulk")
    public ResponseEntity<List<GrievanceResponse>> bulkUpdateStatus(
            @RequestBody BulkStatusUpdateRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(grievanceService.bulkUpdateStatus(request.ticketRefs(), request.update(), actor(httpRequest)));
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring("Bearer ".length()).trim();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** "AccountName (ip)" so the audit trail shows who acted, not just where from. */
    private String actor(HttpServletRequest request) {
        return in.gov.sarthi.result.security.AdminAuthInterceptor.actorName(request) + " (" + clientIp(request) + ")";
    }

    @ExceptionHandler(GrievanceService.GrievanceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(GrievanceService.GrievanceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(GrievanceService.TooManyGrievancesException.class)
    public ResponseEntity<Map<String, String>> handleRateLimited(GrievanceService.TooManyGrievancesException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(GrievanceService.InvalidWebhookUrlException.class)
    public ResponseEntity<Map<String, String>> handleInvalidWebhook(GrievanceService.InvalidWebhookUrlException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
    }
}
