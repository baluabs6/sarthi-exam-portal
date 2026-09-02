package in.gov.sarthi.result.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GrievanceRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6,20}$", message = "rollNumber format is invalid")
        String rollNumber,

        @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{2,40}$", message = "examId format is invalid")
        String examId,

        @NotBlank @Pattern(
                regexp = "^(MARKS_DISCREPANCY|NAME_CORRECTION|CERTIFICATE_ISSUE|OTHER)$",
                message = "category must be one of MARKS_DISCREPANCY, NAME_CORRECTION, CERTIFICATE_ISSUE, OTHER")
        String category,

        @NotBlank @Size(max = 2000)
        String message,

        /**
         * Optional: if provided, a POST is sent here when this ticket's
         * status changes (see WebhookNotifier). Must be a public
         * http(s) URL — validated and rejected if it points at a
         * private/internal address, since accepting an arbitrary
         * candidate-supplied URL to POST to is a textbook SSRF vector
         * otherwise.
         */
        @Size(max = 500)
        String webhookUrl
) {}
