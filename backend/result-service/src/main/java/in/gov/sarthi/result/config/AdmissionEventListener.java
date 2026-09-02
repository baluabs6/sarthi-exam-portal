package in.gov.sarthi.result.config;

import in.gov.sarthi.result.service.ResultLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * By the time an admitted user's browser redirects and calls
 * GET /api/results/{rollNumber}, this listener has often already primed
 * the Redis cache for that roll number — so their very first request,
 * the one moment they're most anxious about, is fast.
 */
@Component
public class AdmissionEventListener {

    private static final Logger log = LoggerFactory.getLogger(AdmissionEventListener.class);
    private final ResultLookupService resultLookupService;

    public AdmissionEventListener(ResultLookupService resultLookupService) {
        this.resultLookupService = resultLookupService;
    }

    @RabbitListener(queues = RabbitMQConfig.ADMITTED_QUEUE)
    public void onAdmitted(Map<String, String> event) {
        String rollNumber = event.get("rollNumber");
        try {
            resultLookupService.getByRollNumber(rollNumber); // populates cache as a side effect
        } catch (Exception ex) {
            // A missing/unpublished result at this point is expected for
            // some users (e.g. not-yet-declared exams) — never let a
            // cache pre-warm failure disrupt the admission flow itself.
            log.debug("Cache pre-warm skipped for {}: {}", rollNumber, ex.getMessage());
        }
    }
}
