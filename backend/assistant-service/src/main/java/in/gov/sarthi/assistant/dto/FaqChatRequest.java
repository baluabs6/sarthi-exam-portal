package in.gov.sarthi.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FaqChatRequest(
        @NotBlank @Size(max = 300) String question
) {}
