package in.gov.sarthi.result.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{6,20}$", message = "rollNumber format is invalid")
        String rollNumber,

        @NotNull
        Boolean helpful,

        @Size(max = 1000)
        String comment
) {}
