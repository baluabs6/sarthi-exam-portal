package in.gov.sarthi.result.dto;

import in.gov.sarthi.result.model.Grievance;
import in.gov.sarthi.result.util.ExtractiveSummarizer;

import java.time.Instant;

public record GrievanceResponse(
        String ticketRef,
        String rollNumber,
        String examId,
        String category,
        String message,
        /** The message's most informative sentence(s), verbatim — see ExtractiveSummarizer. Same as message for short ones. */
        String summary,
        String status,
        String languageHint,
        String adminNote,
        Instant createdAt,
        Instant updatedAt,
        /** Only populated for still-open tickets — see GrievanceService.estimateResolutionDays. Null once resolved/rejected. */
        Double estimatedResolutionDays
) {
    public static GrievanceResponse from(Grievance g) {
        return from(g, null);
    }

    public static GrievanceResponse from(Grievance g, Double estimatedResolutionDays) {
        return new GrievanceResponse(
                g.getTicketRef(), g.getRollNumber(), g.getExamId(), g.getCategory(),
                g.getMessage(), ExtractiveSummarizer.summarize(g.getMessage(), 2),
                g.getStatus(), g.getLanguageHint(), g.getAdminNote(), g.getCreatedAt(), g.getUpdatedAt(),
                estimatedResolutionDays);
    }
}
