package in.gov.sarthi.result.controller;

import in.gov.sarthi.result.model.ResultResponse;
import in.gov.sarthi.result.service.ResultLookupService;
import in.gov.sarthi.result.util.PiiMasker;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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

    public PartnerController(ResultLookupService resultLookupService) {
        this.resultLookupService = resultLookupService;
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

        return ResponseEntity.ok(entries);
    }
}
