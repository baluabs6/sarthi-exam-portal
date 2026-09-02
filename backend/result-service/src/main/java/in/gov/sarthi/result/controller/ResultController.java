package in.gov.sarthi.result.controller;

import in.gov.sarthi.result.security.TicketReplayGuard;
import in.gov.sarthi.result.security.TicketService;
import in.gov.sarthi.result.model.ResultResponse;
import in.gov.sarthi.result.service.ResultLookupService;
import in.gov.sarthi.result.util.PiiMasker;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/results")
public class ResultController {

    private static final Logger log = LoggerFactory.getLogger(ResultController.class);

    private final ResultLookupService resultLookupService;
    private final TicketService ticketService;
    private final TicketReplayGuard replayGuard;

    public ResultController(ResultLookupService resultLookupService, TicketService ticketService, TicketReplayGuard replayGuard) {
        this.resultLookupService = resultLookupService;
        this.ticketService = ticketService;
        this.replayGuard = replayGuard;
    }

    /**
     * The ticket now travels in the Authorization header
     * ("Authorization: Bearer <ticket>") rather than a "?ticket=" query
     * string — query strings are far more likely to end up logged
     * verbatim by intermediate proxies, browser history, or a stray
     * Referer header, none of which apply to a header value on a GET.
     *
     * The ticket must (a) carry a valid signature, (b) be unexpired,
     * (c) match this exact roll number, (d) not have been used before,
     * and (e, soft check only — see class comment) roughly match the IP
     * that originally joined the queue. That last check is deliberately
     * non-blocking: mobile networks and CDNs routinely change a user's
     * visible IP between joining and redeeming, so hard-blocking on
     * mismatch would lock out legitimate users far more often than
     * attackers. It's logged so a real anomaly pattern is still visible
     * to whoever reviews these logs.
     */
    @GetMapping("/{rollNumber}")
    public ResponseEntity<?> getResult(
            @PathVariable String rollNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest request) {

        String ticket = extractBearerToken(authorization);
        var verified = ticketService.verify(ticket);
        if (verified.isEmpty() || !verified.get().rollNumber().equals(rollNumber)) {
            log.warn("Result access denied for {} — invalid/mismatched ticket", PiiMasker.maskRollNumber(rollNumber));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "A valid admission ticket is required. Please join the queue to check this result."
            ));
        }
        if (!replayGuard.tryConsume(verified.get().jti(), Duration.ofMinutes(15), "result")) {
            log.warn("Result access denied for {} — ticket already used", PiiMasker.maskRollNumber(rollNumber));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "message", "This admission ticket has already been used. Please rejoin the queue for another look."
            ));
        }

        String redeemedFromIp = clientIp(request);
        String issuedFromIp = verified.get().issuedFromIp();
        if (issuedFromIp != null && !issuedFromIp.isBlank() && !issuedFromIp.equals(redeemedFromIp)) {
            log.warn("Ticket for {} redeemed from a different IP than it was issued to (soft check, not blocked)",
                    PiiMasker.maskRollNumber(rollNumber));
        }

        log.info("Result served for {}", PiiMasker.maskRollNumber(rollNumber));
        ResultResponse response = resultLookupService.getByRollNumber(rollNumber);
        return ResponseEntity.ok(response);
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

    @ExceptionHandler(ResultLookupService.ResultNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ResultLookupService.ResultNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        // Never leak stack traces to the end user — show a message that
        // tells them what to do next instead.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "Result service is temporarily busy. Please try again shortly."));
    }
}
