package in.gov.sarthi.result.service;

import in.gov.sarthi.result.model.Result;
import in.gov.sarthi.result.model.ResultResponse;
import in.gov.sarthi.result.repository.ResultRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ResultLookupService {

    private final ResultRepository resultRepository;

    public ResultLookupService(ResultRepository resultRepository) {
        this.resultRepository = resultRepository;
    }

    /**
     * Cached in Redis for 10 minutes (see application.yml). Result-day
     * traffic is extremely read-heavy and the same roll numbers are
     * often re-checked (family members refreshing repeatedly) — caching
     * here removes most of that repeat load from Postgres entirely.
     */
    @Cacheable(value = "results", key = "#rollNumber")
    public ResultResponse getByRollNumber(String rollNumber) {
        Result result = resultRepository.findByRollNumber(rollNumber)
                .orElseThrow(() -> new ResultNotFoundException(rollNumber));
        return ResultResponse.from(result);
    }

    public static class ResultNotFoundException extends RuntimeException {
        public ResultNotFoundException(String rollNumber) {
            super("No result found for roll number " + rollNumber);
        }
    }
}
