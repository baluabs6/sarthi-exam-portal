package in.gov.sarthi.queue.controller;

import in.gov.sarthi.queue.dto.JoinRequest;
import in.gov.sarthi.queue.dto.JoinResponse;
import in.gov.sarthi.queue.dto.QueueSnapshot;
import in.gov.sarthi.queue.security.CaptchaService;
import in.gov.sarthi.queue.service.QueueManagerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class QueueController {

    private final QueueManagerService queueManagerService;
    private final CaptchaService captchaService;

    public QueueController(QueueManagerService queueManagerService, CaptchaService captchaService) {
        this.queueManagerService = queueManagerService;
        this.captchaService = captchaService;
    }

    /**
     * Issues a lightweight arithmetic verification challenge that must be
     * solved and submitted along with /queue/join.
     * Backend: GET queue-service /api/queue/captcha
     */
    @GetMapping("/queue/captcha")
    public ResponseEntity<CaptchaService.Challenge> captcha(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(captchaService.generate(clientIp(httpRequest)));
    }

    /**
     * Every request lands here first. It never fails loudly under load —
     * worst case, the user simply gets a larger position number and a
     * longer estimated wait, rather than an error page. A failed CAPTCHA
     * or a filled-in honeypot field is the deliberate exception: that's
     * a 400, not a queue slot.
     */
    @PostMapping("/queue/join")
    public ResponseEntity<?> join(@Valid @RequestBody JoinRequest request, HttpServletRequest httpRequest) {
        // Honeypot: a hidden field real browsers never fill in, but a
        // naive auto-fill bot script often does. Reject silently with
        // the same shape as any other validation failure, so a bot
        // scraping error responses learns nothing about why it failed.
        if (request.website() != null && !request.website().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Could not process your request. Please try again."));
        }

        JoinResponse response = queueManagerService.join(
                request.examId(), request.rollNumber(), request.captchaId(), request.captchaAnswer(),
                clientIp(httpRequest));
        return ResponseEntity.ok(response);
    }

    /**
     * Polling fallback used by the frontend when the WebSocket connection
     * can't be established (some institutional/mobile networks block
     * raw WebSocket upgrades).
     */
    @GetMapping("/queue/status/{queueId}")
    public ResponseEntity<QueueSnapshot> status(@PathVariable String queueId) {
        return ResponseEntity.ok(queueManagerService.snapshot(queueId));
    }

    /**
     * Deliberately not behind the gateway's rate limiter — users should
     * always be able to see system health, even during a total overload,
     * so they know it's not just their connection.
     */
    @GetMapping("/status/health")
    public ResponseEntity<Map<String, String>> health() {
        String status = queueManagerService.loadStatus();
        String message = switch (status) {
            case "critical" -> "Very high traffic — longer waits than usual";
            case "high-load" -> "High traffic — queueing is active";
            default -> "All systems normal";
        };
        return ResponseEntity.ok(Map.of("state", mapState(status), "message", message));
    }

    /**
     * A public, aggregated-only figure (total across all exams, no
     * per-exam breakdown) — the per-exam breakdown stays behind
     * /api/admin/queues since exact per-exam depth could be used to
     * infer relative popularity/timing of unrelated exams.
     */
    @GetMapping("/status/live-count")
    public ResponseEntity<Map<String, Long>> liveCount() {
        return ResponseEntity.ok(Map.of("totalWaiting", queueManagerService.totalWaitingAcrossExams()));
    }

    public record FaqMissReport(String query) {}

    /**
     * Called by the FAQ widget whenever its keyword search comes back
     * empty. Doesn't answer anything — just logs the query so admins can
     * see what people were actually asking that the FAQ content doesn't
     * cover yet, and improve the FAQ set based on real gaps.
     */
    @PostMapping("/status/faq-miss")
    public ResponseEntity<Void> reportFaqMiss(@RequestBody FaqMissReport report) {
        if (report.query() != null && !report.query().isBlank()) {
            String normalized = report.query().trim().toLowerCase();
            if (normalized.length() <= 200) { // matches RequestSizeLimitFilter's spirit — don't let this become a dumping ground
                queueManagerService.recordFaqMiss(normalized);
            }
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(QueueManagerService.InvalidCaptchaException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCaptcha(QueueManagerService.InvalidCaptchaException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
    }

    private String mapState(String loadStatus) {
        return switch (loadStatus) {
            case "critical" -> "down";
            case "high-load" -> "busy";
            default -> "ok";
        };
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
