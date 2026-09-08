package in.gov.sarthi.assistant.controller;

import in.gov.sarthi.assistant.AssistantService;
import in.gov.sarthi.assistant.dto.ClusterLabelRequest;
import in.gov.sarthi.assistant.dto.ClusterLabelResponse;
import in.gov.sarthi.assistant.dto.FaqChatRequest;
import in.gov.sarthi.assistant.dto.FaqChatResponse;
import in.gov.sarthi.assistant.security.AdminKeyGate;
import in.gov.sarthi.assistant.security.AssistantRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantRateLimiter rateLimiter;
    private final AdminKeyGate adminKeyGate;

    public AssistantController(AssistantService assistantService, AssistantRateLimiter rateLimiter, AdminKeyGate adminKeyGate) {
        this.assistantService = assistantService;
        this.rateLimiter = rateLimiter;
        this.adminKeyGate = adminKeyGate;
    }

    /**
     * Candidate-facing RAG chat, used by FaqWidget as a fallback when its
     * local keyword search comes up empty. Never requires a ticket/login
     * (same as the rest of the FAQ surface) — this only ever answers from
     * the public FAQ corpus, nothing candidate-specific or sensitive.
     */
    @PostMapping("/faq-chat")
    public ResponseEntity<FaqChatResponse> faqChat(@Valid @RequestBody FaqChatRequest request, HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire(clientIp(httpRequest))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new FaqChatResponse(true, "Too many questions in a short time — please wait a moment and try again.",
                            java.util.List.of(), false));
        }
        return ResponseEntity.ok(assistantService.answerFaqQuestion(request.question()));
    }

    /**
     * Admin-only enrichment: labels an already-computed grievance cluster
     * (see /api/grievances/admin/clusters in result-service) with a short
     * human-readable summary. Gated by the same admin key used elsewhere.
     */
    @PostMapping("/label-cluster")
    public ResponseEntity<?> labelCluster(
            @Valid @RequestBody ClusterLabelRequest request,
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            HttpServletRequest httpRequest) {

        if (!adminKeyGate.isValid(adminKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Admin key missing or invalid"));
        }
        if (!rateLimiter.tryAcquire(clientIp(httpRequest))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", "Too many requests — please wait a moment."));
        }
        ClusterLabelResponse response = assistantService.labelCluster(request.sampleMessage(), request.clusterSize());
        return ResponseEntity.ok(response);
    }

    /** Lets the frontend show/hide "Ask AI" affordances without guessing from error responses. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("aiEnabled", assistantService.isAiEnabled()));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
