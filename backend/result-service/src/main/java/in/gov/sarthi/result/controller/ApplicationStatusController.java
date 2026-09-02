package in.gov.sarthi.result.controller;

import in.gov.sarthi.result.model.ApplicationStatusHistory;
import in.gov.sarthi.result.repository.ApplicationStatusHistoryRepository;
import in.gov.sarthi.result.util.PiiMasker;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Application status timeline — SUBMITTED → VERIFIED →
 * ADMIT_CARD_ISSUED → RESULT_DECLARED — instead of a single
 * point-in-time result lookup.
 *
 * Deliberately public/unticketed for reads (unlike /api/results/**):
 * a stage name alone ("verified", "admit card issued") carries far less
 * sensitivity than an actual score, and forcing every status check
 * through the queue-and-ticket flow meant to protect result-day load
 * would add friction with no real security benefit here. Still
 * rate-limited at the gateway like every other public endpoint.
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationStatusController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationStatusController.class);

    private final ApplicationStatusHistoryRepository repository;

    public ApplicationStatusController(ApplicationStatusHistoryRepository repository) {
        this.repository = repository;
    }

    public record TimelineEntry(String stage, String note, String changedAt) {}

    @GetMapping("/{rollNumber}/timeline")
    public ResponseEntity<List<TimelineEntry>> timeline(
            @PathVariable String rollNumber,
            @RequestParam String examId) {
        List<TimelineEntry> entries = repository
                .findByRollNumberAndExamIdOrderByChangedAtAsc(rollNumber, examId).stream()
                .map(h -> new TimelineEntry(h.getStage(), h.getNote(), h.getChangedAt().toString()))
                .toList();
        return ResponseEntity.ok(entries);
    }

    public record AdvanceStageRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6,20}$") String rollNumber,
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{2,40}$") String examId,
            @NotBlank @Pattern(regexp = "^(SUBMITTED|VERIFIED|ADMIT_CARD_ISSUED|RESULT_DECLARED)$") String stage,
            String note
    ) {}

    /** Admin-only (gated by AdminAuthInterceptor via WebConfig, same as grievance admin routes). */
    @PostMapping("/admin/advance")
    public ResponseEntity<TimelineEntry> advanceStage(@Valid @RequestBody AdvanceStageRequest request) {
        ApplicationStatusHistory saved = repository.save(new ApplicationStatusHistory(
                request.rollNumber(), request.examId(), request.stage(), request.note()));
        log.info("Application stage advanced for {} ({}) -> {}",
                PiiMasker.maskRollNumber(request.rollNumber()), request.examId(), request.stage());
        return ResponseEntity.ok(new TimelineEntry(saved.getStage(), saved.getNote(), saved.getChangedAt().toString()));
    }
}
