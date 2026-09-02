package in.gov.sarthi.result.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GrievanceStatusUpdateRequest(
        @NotBlank @Pattern(regexp = "^(RAISED|UNDER_REVIEW|RESOLVED|REJECTED)$")
        String status,

        @Size(max = 2000)
        String adminNote
) {}
