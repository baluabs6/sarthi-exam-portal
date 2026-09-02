package in.gov.sarthi.queue.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * A deliberately lightweight arithmetic CAPTCHA, with adaptive difficulty.
 *
 * This is not meant to stop a determined attacker running a real
 * solver — it's meant to raise the cost of the common case: a naive
 * script hammering {@code /api/queue/join} to flood the queue or probe
 * roll numbers. Answers are single-use and stored in Redis with a short
 * TTL, keyed by a random captchaId so the answer never round-trips to
 * the browser.
 *
 * Difficulty scales with an IP's own recent failure count — a genuine
 * IP that fat-fingers one answer barely notices, while an IP grinding
 * through attempts gets progressively harder arithmetic. This needs no
 * model, just a counter, and is a real adaptive mechanism rather than a
 * flat difficulty for everyone.
 */
@Service
public class CaptchaService {

    private static final String KEY_PREFIX = "captcha:";
    private static final String FAILURE_PREFIX = "captcha:failures:";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Challenge(String captchaId, String question) {}

    public Challenge generate(String clientIp) {
        int failures = recentFailureCount(clientIp);
        int a, b;
        String question;

        if (failures >= 6) {
            // Three-term addition — meaningfully harder to script blindly
            // without also having to parse a slightly less predictable format.
            int c = 1 + random.nextInt(9);
            a = 1 + random.nextInt(9);
            b = 1 + random.nextInt(9);
            question = a + " + " + b + " + " + c + " = ?";
            String captchaId = UUID.randomUUID().toString();
            redis.opsForValue().set(KEY_PREFIX + captchaId, String.valueOf(a + b + c), TTL);
            return new Challenge(captchaId, question);
        } else if (failures >= 3) {
            // Larger operands, still simple addition.
            a = 10 + random.nextInt(40);
            b = 10 + random.nextInt(40);
            question = a + " + " + b + " = ?";
        } else {
            a = 1 + random.nextInt(9);
            b = 1 + random.nextInt(9);
            question = a + " + " + b + " = ?";
        }

        String captchaId = UUID.randomUUID().toString();
        redis.opsForValue().set(KEY_PREFIX + captchaId, String.valueOf(a + b), TTL);
        return new Challenge(captchaId, question);
    }

    /** Single-use: the stored answer is deleted whether or not it matched. Tracks failures per IP for adaptive difficulty. */
    public boolean verify(String captchaId, String submittedAnswer, String clientIp) {
        if (captchaId == null || submittedAnswer == null) {
            recordFailure(clientIp);
            return false;
        }
        String key = KEY_PREFIX + captchaId;
        String expected = redis.opsForValue().get(key);
        redis.delete(key);
        boolean correct = expected != null && expected.equals(submittedAnswer.trim());
        if (!correct) {
            recordFailure(clientIp);
        } else {
            redis.delete(FAILURE_PREFIX + clientIp);
        }
        return correct;
    }

    private int recentFailureCount(String clientIp) {
        String value = redis.opsForValue().get(FAILURE_PREFIX + clientIp);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private void recordFailure(String clientIp) {
        if (clientIp == null) return;
        String key = FAILURE_PREFIX + clientIp;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, FAILURE_WINDOW);
        }
    }
}
