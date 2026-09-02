package in.gov.sarthi.queue.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues short-lived, signed "admission tickets".
 *
 * Previously, once a user's queue position was resolved, the frontend
 * just forwarded the raw {@code queueId} to result-service as a
 * "?ticket=" query param that was never actually checked — so anyone who
 * knew or guessed a roll number could call
 * {@code GET /api/results/{rollNumber}} directly and skip the queue
 * entirely. This class closes that gap: a ticket is only minted here,
 * the moment {@link in.gov.sarthi.queue.service.QueueManagerService}
 * confirms a user has actually been admitted, and it is cryptographically
 * bound to that exact rollNumber + examId so it can't be replayed for a
 * different roll number.
 */
@Service
public class TicketService {

    private final SecretKey key;
    private final Duration ttl;

    public TicketService(
            @Value("${ticket.signing-secret}") String signingSecret,
            @Value("${ticket.ttl-seconds:900}") long ttlSeconds) {
        // HS256 needs >= 256 bits; pad/hash short secrets defensively so a
        // short dev value in application.yml can't blow up key derivation.
        byte[] raw = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(raw.length >= 32 ? raw : padTo32(raw));
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public String issue(String examId, String rollNumber, String queueId, String issuedFromIp) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(java.util.UUID.randomUUID().toString()) // "jti" — lets result-service enforce single-use
                .subject(rollNumber)
                .claim("examId", examId)
                .claim("queueId", queueId)
                .claim("ip", issuedFromIp) // soft-bound at redemption — see result-service TicketService
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    private static byte[] padTo32(byte[] raw) {
        byte[] padded = new byte[32];
        for (int i = 0; i < 32; i++) {
            padded[i] = raw[i % raw.length];
        }
        return padded;
    }
}
