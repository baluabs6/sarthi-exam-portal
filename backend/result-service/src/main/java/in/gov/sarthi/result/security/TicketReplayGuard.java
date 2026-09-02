package in.gov.sarthi.result.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * A signed ticket alone only proves the holder genuinely came through the
 * queue — it doesn't stop that same ticket being replayed to fetch the
 * result again and again within its validity window (e.g. a shared link
 * or a saved browser history entry). This makes each ticket's unique
 * "jti" claim single-use: the first successful verification consumes it
 * atomically, and any later attempt with the same jti is rejected even
 * though the signature and expiry both still check out.
 *
 * Uses SETNX-style atomic "set if absent" (via ValueOperations.setIfAbsent)
 * so two near-simultaneous requests with the same ticket can't both slip
 * through in a race.
 */
@Service
public class TicketReplayGuard {

    private static final String KEY_PREFIX = "ticket:used:";

    private final StringRedisTemplate redis;

    public TicketReplayGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @param resource a short tag identifying which endpoint is consuming
     *                 the ticket (e.g. "result", "admit-card") — the same
     *                 ticket can be redeemed once per resource rather
     *                 than being globally single-use, since a candidate
     *                 legitimately wants both their result and their
     *                 admit card from one genuine queue turn.
     * @return true if this is the first use of this ticket for this
     *         resource (caller should proceed), false if already consumed.
     */
    public boolean tryConsume(String jti, Duration ticketTtl, String resource) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        ValueOperations<String, String> ops = redis.opsForValue();
        Boolean firstUse = ops.setIfAbsent(KEY_PREFIX + resource + ":" + jti, "1", ticketTtl);
        return Boolean.TRUE.equals(firstUse);
    }
}
