package in.gov.sarthi.queue.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Without this, a wrong X-Admin-Key just returns 401 with no limit on
 * retries — an attacker (or a leaked/rotated-but-not-yet-updated key
 * from a script) could brute-force or hammer the admin endpoints
 * indefinitely. This tracks failed attempts per source IP in Redis and
 * imposes a short lockout after too many in a row, with the counter
 * reset on any successful attempt.
 */
@Service
public class AdminLockoutTracker {

    private static final String KEY_PREFIX = "admin:lockout:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public AdminLockoutTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLockedOut(String clientIp) {
        String value = redis.opsForValue().get(KEY_PREFIX + "locked:" + clientIp);
        return value != null;
    }

    public void recordFailure(String clientIp) {
        String attemptsKey = KEY_PREFIX + "attempts:" + clientIp;
        Long attempts = redis.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1) {
            redis.expire(attemptsKey, WINDOW);
        }
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            redis.opsForValue().set(KEY_PREFIX + "locked:" + clientIp, "1", LOCKOUT);
        }
    }

    public void recordSuccess(String clientIp) {
        redis.delete(KEY_PREFIX + "attempts:" + clientIp);
        redis.delete(KEY_PREFIX + "locked:" + clientIp);
    }
}
