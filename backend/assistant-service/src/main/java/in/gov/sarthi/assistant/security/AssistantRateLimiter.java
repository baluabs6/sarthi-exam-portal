package in.gov.sarthi.assistant.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * A second, tighter rate limit on top of the gateway's own — the
 * gateway protects against traffic floods generically, but LLM calls
 * specifically cost real money and third-party quota per request, so
 * this keeps a much lower per-IP ceiling than the gateway route allows,
 * independent of gateway configuration drifting over time.
 */
@Service
public class AssistantRateLimiter {

    private static final String KEY_PREFIX = "assistant:rate:";
    private static final int MAX_REQUESTS_PER_WINDOW = 8;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;

    public AssistantRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return true if this request is allowed to proceed. */
    public boolean tryAcquire(String clientIp) {
        String key = KEY_PREFIX + clientIp;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, WINDOW);
        }
        return count != null && count <= MAX_REQUESTS_PER_WINDOW;
    }
}
