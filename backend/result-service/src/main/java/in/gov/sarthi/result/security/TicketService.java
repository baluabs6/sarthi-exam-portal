package in.gov.sarthi.result.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Verifies the signed admission ticket minted by queue-service the
 * instant a user is actually let out of the waiting room.
 *
 * Before this existed, {@code GET /api/results/{rollNumber}} accepted a
 * "ticket" query parameter but never checked it at all — so the queue
 * could be skipped entirely just by guessing/knowing a roll number and
 * calling the endpoint directly. Now a request is only served if it
 * carries a ticket that (a) has a valid signature from the shared
 * secret, (b) has not expired, and (c) was issued for this exact roll
 * number.
 */
@Service
public class TicketService {

    private final SecretKey key;

    public TicketService(@Value("${ticket.signing-secret}") String signingSecret) {
        byte[] raw = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(raw.length >= 32 ? raw : padTo32(raw));
    }

    /** Verified ticket claims, or empty if invalid/expired/tampered. */
    public record VerifiedTicket(String rollNumber, String jti, String issuedFromIp) {}

    public Optional<VerifiedTicket> verify(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(ticket)
                    .getPayload();
            return Optional.of(new VerifiedTicket(claims.getSubject(), claims.getId(), claims.get("ip", String.class)));
        } catch (ExpiredJwtException | JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Retained for any caller that only needs the rollNumber. */
    public Optional<String> verifyAndGetRollNumber(String ticket) {
        return verify(ticket).map(VerifiedTicket::rollNumber);
    }

    private static byte[] padTo32(byte[] raw) {
        byte[] padded = new byte[32];
        for (int i = 0; i < 32; i++) {
            padded[i] = raw[i % raw.length];
        }
        return padded;
    }
}
