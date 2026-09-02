package in.gov.sarthi.result.util;

/**
 * Roll numbers are personal identifiers. Logging them in full means any
 * log-aggregation tool (CloudWatch, ELK, etc.) ends up holding plaintext
 * PII indefinitely, often with looser access controls than the primary
 * database. This masks all but the first 4 and last 3 characters, which
 * is normally enough for a support engineer to correlate a log line with
 * a user report without the full number being recoverable from logs
 * alone.
 */
public final class PiiMasker {

    private PiiMasker() {}

    public static String maskRollNumber(String rollNumber) {
        if (rollNumber == null || rollNumber.length() <= 7) {
            return "****";
        }
        String prefix = rollNumber.substring(0, 4);
        String suffix = rollNumber.substring(rollNumber.length() - 3);
        return prefix + "****" + suffix;
    }

    /**
     * Strips CR/LF (and other control characters) from user-supplied text
     * before it's ever interpolated into a log statement. Without this, a
     * grievance message or feedback comment containing an embedded
     * newline could forge what looks like a separate, fake log line —
     * "log injection" — misleading anyone reviewing logs later.
     */
    public static String sanitizeForLog(String input) {
        if (input == null) return "";
        return input.replaceAll("[\\r\\n\\t\\x00-\\x1F]", " ").trim();
    }
}
