package in.gov.sarthi.queue.dto;

public record JoinResponse(String queueId, int position, long estimatedWaitSeconds) {}
