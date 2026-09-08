package in.gov.sarthi.queue.controller;

import in.gov.sarthi.queue.model.AdminAuditEvent;
import in.gov.sarthi.queue.model.AdminAuditRepository;
import in.gov.sarthi.queue.security.AdminAccount;
import in.gov.sarthi.queue.security.AdminAuthInterceptor;
import in.gov.sarthi.queue.service.AuditAnomalyDetector;
import in.gov.sarthi.queue.service.QueueManagerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Everything here requires the X-Admin-Key header (see
 * AdminAuthInterceptor + AdminWebConfig, including the failed-attempt
 * lockout). This gives exam authority staff a live view of queue depth
 * and the ability to tune the admission rate during an actual
 * result-day event, without needing a redeploy.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final List<String> ACTIVE_EXAMS = List.of("NEET-UG-2026", "JEE-MAIN-2026", "SSC-CGL-2026");

    private final QueueManagerService queueManagerService;
    private final AdminAuditRepository adminAuditRepository;
    private final AuditAnomalyDetector auditAnomalyDetector;

    public AdminController(QueueManagerService queueManagerService, AdminAuditRepository adminAuditRepository,
                            AuditAnomalyDetector auditAnomalyDetector) {
        this.queueManagerService = queueManagerService;
        this.adminAuditRepository = adminAuditRepository;
        this.auditAnomalyDetector = auditAnomalyDetector;
    }

    /**
     * Lets the admin dashboard know who's actually logged in and what
     * they're allowed to do, instead of showing every admin an identical
     * UI and letting them discover their role only when a write 403s.
     * Both queue-service and result-service validate the same
     * ADMIN_ACCOUNTS_JSON (see .env), so a single call here accurately
     * reflects permissions on both — as long as that env var is kept in
     * sync across the two services, which docker-compose.yml and the
     * Terraform in infra/ both already do by construction (one shared
     * value, not two independently-set ones).
     */
    @GetMapping("/whoami")
    public ResponseEntity<Map<String, Object>> whoami(HttpServletRequest request) {
        AdminAccount account = (AdminAccount) request.getAttribute(AdminAuthInterceptor.REQUEST_ATTR_ACCOUNT);
        return ResponseEntity.ok(Map.of(
                "name", account.name(),
                "role", account.role().name(),
                // Mirrors the two interceptors' actual enforcement exactly,
                // rather than the frontend guessing/duplicating the rule —
                // if the rule ever changes here or in result-service's
                // interceptor, this is the one place the frontend needs
                // to reflect it correctly.
                "canTuneQueue", account.role() == AdminAccount.AdminRole.SUPER_ADMIN || account.role() == AdminAccount.AdminRole.QUEUE_OPERATOR,
                "canManageGrievances", account.role() == AdminAccount.AdminRole.SUPER_ADMIN
        ));
    }

    @GetMapping("/queues")
    public ResponseEntity<Map<String, Object>> queueOverview() {
        Map<String, Object> projections = new java.util.LinkedHashMap<>();
        Map<String, Object> possiblyAbandoned = new java.util.LinkedHashMap<>();
        for (String exam : ACTIVE_EXAMS) {
            Long minutes = queueManagerService.projectedMinutesToCritical(exam, 5000);
            if (minutes != null) projections.put(exam, minutes);
            int abandoned = queueManagerService.estimatePossiblyAbandoned(exam, java.time.Duration.ofMinutes(10).toMillis());
            if (abandoned > 0) possiblyAbandoned.put(exam, abandoned);
        }
        return ResponseEntity.ok(Map.of(
                "depthByExam", queueManagerService.queueDepthByExam(ACTIVE_EXAMS),
                "admitPerTick", queueManagerService.getAdmitPerTick(),
                "observedAdmitRate", queueManagerService.getObservedAdmitRate(),
                "systemLoad", queueManagerService.loadStatus(),
                "minutesToCriticalByExam", projections,
                "possiblyAbandonedByExam", possiblyAbandoned
        ));
    }

    public record AdmitRateUpdate(int admitPerTick) {}

    @PatchMapping("/admit-rate")
    public ResponseEntity<?> updateAdmitRate(@RequestBody AdmitRateUpdate update, HttpServletRequest request) {
        try {
            int previous = queueManagerService.getAdmitPerTick();
            queueManagerService.setAdmitPerTick(update.admitPerTick());
            adminAuditRepository.save(new AdminAuditEvent(
                    "ADMIT_RATE_CHANGED",
                    previous + " -> " + update.admitPerTick(),
                    in.gov.sarthi.queue.security.AdminAuthInterceptor.actorName(request) + " (" + clientIp(request) + ")"
            ));
            return ResponseEntity.ok(Map.of("admitPerTick", queueManagerService.getAdmitPerTick()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    /** Recent admin activity, so more than one staff member sharing the same key stays accountable to a shared log. */
    @GetMapping("/audit-log")
    public ResponseEntity<List<AdminAuditEvent>> recentAdminActions() {
        return ResponseEntity.ok(adminAuditRepository.findTop50ByOrderByTimestampDesc());
    }

    /** What people searched the FAQ widget for that returned nothing — real content gaps, not guesses. */
    @GetMapping("/faq-gaps")
    public ResponseEntity<Map<String, Double>> faqGaps() {
        return ResponseEntity.ok(queueManagerService.topFaqGaps(20));
    }

    /** IPs that have tried an unusually high number of distinct roll numbers recently — a signal of result-enumeration/scraping attempts, not proof. */
    @GetMapping("/enumeration-alerts")
    public ResponseEntity<List<String>> enumerationAlerts() {
        return ResponseEntity.ok(queueManagerService.suspectedEnumerationIps());
    }

    /** Statistical (not ML) admission-burst detection over the last hour, per exam — see AuditAnomalyDetector. */
    @GetMapping("/anomalies")
    public ResponseEntity<Map<String, List<AuditAnomalyDetector.AnomalyWindow>>> anomalies() {
        Map<String, List<AuditAnomalyDetector.AnomalyWindow>> result = new java.util.LinkedHashMap<>();
        for (String exam : ACTIVE_EXAMS) {
            List<AuditAnomalyDetector.AnomalyWindow> anomalies = auditAnomalyDetector.detectAdmissionBursts(exam);
            if (!anomalies.isEmpty()) result.put(exam, anomalies);
        }
        return ResponseEntity.ok(result);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
