package in.gov.sarthi.result.controller;

import in.gov.sarthi.result.model.ResultResponse;
import in.gov.sarthi.result.service.PartnerUsageTracker;
import in.gov.sarthi.result.service.ResultLookupService;
import in.gov.sarthi.result.util.PiiMasker;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * A separate channel for coaching institutes / partner organizations to
 * check results for many of their own students at once, instead of
 * routing that entirely legitimate bulk use case through the
 * single-candidate queue-and-ticket flow that exists to protect against
 * anonymous mass traffic. Gated by X-Partner-Key (PartnerAuthInterceptor)
 * — a completely different credential from both candidate tickets and
 * the admin key, and rate-limited separately at the gateway.
 *
 * Deliberately capped per request (see MAX_BULK_SIZE) so even a
 * legitimate partner key can't be used to enumerate the entire results
 * table in one call.
 */
@RestController
@RequestMapping("/api/partner")
public class PartnerController {

    private static final Logger log = LoggerFactory.getLogger(PartnerController.class);
    private static final int MAX_BULK_SIZE = 100;

    private final ResultLookupService resultLookupService;
    private final PartnerUsageTracker usageTracker;

    public PartnerController(ResultLookupService resultLookupService, PartnerUsageTracker usageTracker) {
        this.resultLookupService = resultLookupService;
        this.usageTracker = usageTracker;
    }

    public record BulkResultRequest(
            @NotEmpty @Size(max = MAX_BULK_SIZE, message = "A maximum of " + MAX_BULK_SIZE + " roll numbers per request")
            List<String> rollNumbers
    ) {}

    public record BulkResultEntry(String rollNumber, ResultResponse result, String error) {}

    @PostMapping("/results/bulk")
    public ResponseEntity<List<BulkResultEntry>> bulkResults(@Valid @RequestBody BulkResultRequest request) {
        log.info("Partner bulk lookup for {} roll numbers", request.rollNumbers().size());

        List<BulkResultEntry> entries = request.rollNumbers().stream()
                .map(rollNumber -> {
                    try {
                        return new BulkResultEntry(rollNumber, resultLookupService.getByRollNumber(rollNumber), null);
                    } catch (ResultLookupService.ResultNotFoundException e) {
                        return new BulkResultEntry(rollNumber, null, "Not found");
                    } catch (Exception e) {
                        log.warn("Partner bulk lookup failed for {}", PiiMasker.maskRollNumber(rollNumber));
                        return new BulkResultEntry(rollNumber, null, "Lookup failed");
                    }
                })
                .toList();

        long errorCount = entries.stream().filter(e -> e.error() != null).count();
        usageTracker.recordCall(request.rollNumbers().size(), (int) errorCount);

        return ResponseEntity.ok(entries);
    }

    /**
     * Self-service usage visibility — see PartnerUsageTracker's class
     * comment for the gap this closes. Gated by the same X-Partner-Key
     * as every other endpoint here (see PartnerAuthInterceptor /
     * AdminWebConfig-equivalent registration), not a new credential.
     */
    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> usage() {
        var snapshot = usageTracker.snapshot();
        return ResponseEntity.ok(Map.of(
                "callsToday", snapshot.callsToday(),
                "rollNumbersLookedUpToday", snapshot.rollNumbersLookedUpToday(),
                "errorsToday", snapshot.errorsToday(),
                "lastCallAt", snapshot.lastCallAt() == null ? "" : snapshot.lastCallAt(),
                "maxRollNumbersPerRequest", MAX_BULK_SIZE,
                "note", "Gateway-enforced rate limit is separate and IP-based (10 req/s, burst 20) — this is self-reported call volume, not the live limiter state."
        ));
    }
}
