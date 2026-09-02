package in.gov.sarthi.result.controller;

import in.gov.sarthi.result.dto.FeedbackRequest;
import in.gov.sarthi.result.model.ResultFeedback;
import in.gov.sarthi.result.repository.ResultFeedbackRepository;
import in.gov.sarthi.result.util.PiiMasker;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A quick thumbs-up/down + optional comment after viewing a result —
 * feeds portal improvements, nothing more.
 *
 * The urgency score below is a small fixed keyword list, not a trained
 * sentiment model — it exists to let staff sort a pile of comments
 * without reading every one, not to make any real judgment about the
 * writer's emotional state. A genuine sentiment classifier would need a
 * trained model or a hosted classification API; this is the honest,
 * scoped version of that idea.
 */
@RestController
@RequestMapping("/api/results")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private static final String[] HIGH_URGENCY_TERMS = {"urgent", "scam", "fraud", "cheat", "legal", "court", "police"};
    private static final String[] MEDIUM_URGENCY_TERMS = {"wrong", "error", "mistake", "broken", "not working", "failed", "issue"};

    private final ResultFeedbackRepository repository;

    public FeedbackController(ResultFeedbackRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> submit(@Valid @RequestBody FeedbackRequest request) {
        int urgency = scoreUrgency(request.helpful(), request.comment());
        repository.save(new ResultFeedback(request.rollNumber(), request.helpful(), request.comment(), urgency));
        // Comment text is free-form user input — sanitized before logging
        // to prevent a crafted comment from forging fake log lines.
        log.info("Feedback from {}: helpful={}, urgency={}, comment=\"{}\"",
                PiiMasker.maskRollNumber(request.rollNumber()),
                request.helpful(),
                urgency,
                PiiMasker.sanitizeForLog(request.comment()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "Thanks for the feedback."));
    }

    private int scoreUrgency(boolean helpful, String comment) {
        if (comment == null || comment.isBlank()) return helpful ? 0 : 1;
        String lower = comment.toLowerCase();
        for (String term : HIGH_URGENCY_TERMS) {
            if (lower.contains(term)) return 3;
        }
        for (String term : MEDIUM_URGENCY_TERMS) {
            if (lower.contains(term)) return 2;
        }
        return helpful ? 0 : 1;
    }
}
