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
        this.key = Keys.hmacShaKeyFor(deriveKeyBytes(signingSecret));
    }

    /**
     * Always derives a full 256-bit key via SHA-256, regardless of the
     * raw secret's length, instead of the previous approach (use the raw
     * bytes as-is if >= 32 bytes, otherwise repeat them to pad to 32).
     * Byte-repetition padding for a short secret produces a key with
     * much less real entropy than its length suggests (e.g. an 8-byte
     * secret repeated 4x is still only 8 bytes of real randomness) —
     * hashing removes that specific weakness. This does NOT make a
     * genuinely weak/short secret strong (hashing a guessable secret
     * still yields a guessable key) — TICKET_SIGNING_SECRET still needs
     * real entropy (see .env.example); this just removes the additional,
     * avoidable weakness the old padding scheme added on top of that.
     */
    private static byte[] deriveKeyBytes(String secret) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not derive ticket signing key", e);
        }
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
}


