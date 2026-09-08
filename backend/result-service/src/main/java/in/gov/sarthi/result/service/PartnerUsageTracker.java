package in.gov.sarthi.result.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Previously a partner organization had NO way to see their own usage —
 * they'd find out they were near their limit only by starting to get
 * 429s, with no self-service way to check remaining headroom or recent
 * call history without going through result-service's admin team
 * directly. This gives them a live, self-reported view via their own
 * X-Partner-Key.
 *
 * Deliberately a SEPARATE counter from the gateway's own rate limiter
 * (which is IP-keyed, not partner-key-keyed, and Redis-bucket internals
 * aren't meant to be read back out) — this is purely for visibility,
 * not enforcement.
 */
@Service
public class PartnerUsageTracker {

    private static final String CALLS_KEY_PREFIX = "partner:usage:calls:";
    private static final String ROLL_NUMBERS_KEY_PREFIX = "partner:usage:roll-numbers:";
    private static final String ERRORS_KEY_PREFIX = "partner:usage:errors:";
    private static final String LAST_CALL_KEY = "partner:usage:last-call-at";
    private static final Duration DAILY_KEY_TTL = Duration.ofDays(2); // outlive the day it's for, so "today so far" is always readable

    private final StringRedisTemplate redis;

    public PartnerUsageTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void recordCall(int rollNumberCount, int errorCount) {
        String today = todayKey();
        increment(CALLS_KEY_PREFIX + today, 1);
        increment(ROLL_NUMBERS_KEY_PREFIX + today, rollNumberCount);
        if (errorCount > 0) {
            increment(ERRORS_KEY_PREFIX + today, errorCount);
        }
        redis.opsForValue().set(LAST_CALL_KEY, Instant.now().toString());
    }

    public record UsageSnapshot(long callsToday, long rollNumbersLookedUpToday, long errorsToday, String lastCallAt) {}

    public UsageSnapshot snapshot() {
        String today = todayKey();
        return new UsageSnapshot(
                readLong(CALLS_KEY_PREFIX + today),
                readLong(ROLL_NUMBERS_KEY_PREFIX + today),
                readLong(ERRORS_KEY_PREFIX + today),
                redis.opsForValue().get(LAST_CALL_KEY)
        );
    }

    private void increment(String key, long by) {
        Long newValue = redis.opsForValue().increment(key, by);
        if (newValue != null && newValue == by) {
            redis.expire(key, DAILY_KEY_TTL);
        }
    }

    private long readLong(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private String todayKey() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }
}
