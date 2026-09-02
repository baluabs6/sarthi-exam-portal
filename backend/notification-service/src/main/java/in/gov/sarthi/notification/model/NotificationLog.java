package in.gov.sarthi.notification.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "notification_log")
public class NotificationLog {

    @Id
    private String id;
    private String rollNumber;
    private String message;
    private String channel;
    private String outcome; // DELIVERED | FAILED
    private String failureReason;
    private Instant timestamp;

    protected NotificationLog() {}

    private NotificationLog(String rollNumber, String message, String channel, String outcome, String failureReason) {
        this.rollNumber = rollNumber;
        this.message = message;
        this.channel = channel;
        this.outcome = outcome;
        this.failureReason = failureReason;
        this.timestamp = Instant.now();
    }

    public static NotificationLog delivered(String rollNumber, String message, String channel) {
        return new NotificationLog(rollNumber, message, channel, "DELIVERED", null);
    }

    public static NotificationLog failed(String rollNumber, String message, String reason) {
        return new NotificationLog(rollNumber, message, "none", "FAILED", reason);
    }

    public String getId() { return id; }
    public String getRollNumber() { return rollNumber; }
    public String getMessage() { return message; }
    public String getChannel() { return channel; }
    public String getOutcome() { return outcome; }
    public String getFailureReason() { return failureReason; }
    public Instant getTimestamp() { return timestamp; }
}
