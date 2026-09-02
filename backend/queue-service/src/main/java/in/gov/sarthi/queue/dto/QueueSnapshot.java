package in.gov.sarthi.queue.dto;

/**
 * The single shape sent to the browser, whether via WebSocket push or the
 * REST polling fallback — keeping one contract for both transports means
 * the frontend doesn't need to know which path delivered the update.
 */
public record QueueSnapshot(
        String queueId,
        Integer position,
        Integer aheadOfYou,
        Long estimatedWaitSeconds,
        String status,       // "normal" | "high-load" | "critical"
        boolean admitted,
        String resultTicket  // signed, short-lived — only present once admitted==true
) {
    public static QueueSnapshot waiting(String queueId, int position, int aheadOfYou, long waitSeconds, String status) {
        return new QueueSnapshot(queueId, position, aheadOfYou, waitSeconds, status, false, null);
    }

    public static QueueSnapshot admittedNow(String queueId, String resultTicket) {
        return new QueueSnapshot(queueId, 0, 0, 0L, "normal", true, resultTicket);
    }
}
