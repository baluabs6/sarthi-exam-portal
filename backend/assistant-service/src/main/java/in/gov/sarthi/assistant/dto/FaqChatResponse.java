package in.gov.sarthi.assistant.dto;

import java.util.List;

public record FaqChatResponse(
        boolean aiEnabled,
        String answer,
        List<String> sourceIds,
        boolean grounded
) {
    public static FaqChatResponse disabled() {
        return new FaqChatResponse(false,
                "The AI assistant isn't configured on this deployment. Try the keyword search above, "
                        + "or raise a grievance for anything result-specific.",
                List.of(), false);
    }

    public static FaqChatResponse noMatch() {
        return new FaqChatResponse(true,
                "I don't have information on that in the FAQ. Please raise a grievance for "
                        + "anything result-specific, or check the official notification for your exam.",
                List.of(), false);
    }
}
