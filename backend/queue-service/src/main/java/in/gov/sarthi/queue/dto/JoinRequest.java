package in.gov.sarthi.queue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * examId and rollNumber are now whitelist-validated (not just
 * "not blank") before they ever reach Redis key construction or any
 * downstream query — this closes off key-injection-style abuse where a
 * crafted rollNumber/examId could manipulate Redis key names.
 *
 * captchaId/captchaAnswer must match a challenge previously issued by
 * GET /api/queue/captcha (see CaptchaService) — this is checked before a
 * queue slot is created.
 */
public record JoinRequest(
        @NotBlank(message = "examId is required")
        @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{2,40}$", message = "examId format is invalid")
        String examId,

        @NotBlank(message = "rollNumber is required")
        @Pattern(regexp = "^[A-Za-z0-9]{6,20}$", message = "rollNumber format is invalid")
        String rollNumber,

        @NotBlank(message = "Please complete the verification challenge")
        String captchaId,

        @NotBlank(message = "Please answer the verification challenge")
        String captchaAnswer,

        // Honeypot: left blank by real users (hidden via CSS in the
        // frontend form), filled in by naive bots that auto-fill every
        // input. Not annotated @NotBlank — its whole purpose is to stay
        // empty.
        String website
) {}
