package in.gov.sarthi.assistant.util;

import java.util.regex.Pattern;

/**
 * Best-effort redaction applied to any candidate-submitted free text
 * before it leaves this system for a third-party LLM API. This is the
 * fix for a real gap: labelCluster used to send a grievance's raw
 * sample message (which can legitimately contain a roll number, phone
 * number, or email a candidate typed into their complaint) to Anthropic
 * with no redaction at all.
 *
 * Pattern-based, not a real PII/NER model — same honest tradeoff as
 * WebhookNotifier's SSRF check: a meaningful mitigation, not a complete
 * one. It reliably catches structured identifiers (roll numbers, phone
 * numbers, emails) but can't catch something like "my name is Aarav" —
 * that would need a real named-entity-recognition pass, which is a
 * larger, separate piece of work. This is applied on TOP OF admins
 * already having whatever access they'd otherwise have to this
 * grievance (they're not gaining new visibility here) — the point is
 * narrowly to keep the raw text from leaving the system unredacted.
 */
public final class FreeTextRedactor {

    // 6+ consecutive digits (roll numbers here are 11, but this also
    // catches shorter ones and phone numbers) — deliberately broad
    // rather than trying to match this system's exact roll-number format,
    // since exam ID / other numeric identifiers benefit from the same
    // treatment and a false positive here just costs the model a little
    // context, not correctness.
    private static final Pattern DIGIT_SEQUENCE = Pattern.compile("\\b\\d{6,}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");

    private FreeTextRedactor() {}

    public static String redact(String text) {
        if (text == null) return null;
        String redacted = DIGIT_SEQUENCE.matcher(text).replaceAll("[redacted-number]");
        redacted = EMAIL.matcher(redacted).replaceAll("[redacted-email]");
        return redacted;
    }
}
