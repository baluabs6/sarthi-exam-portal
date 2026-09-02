package in.gov.sarthi.result.util;

/**
 * A rough language guess based on which Unicode script a grievance
 * message is written in — not real language detection (which would
 * distinguish Hindi from Marathi, both Devanagari, for instance), but
 * enough to route "this is in a script our English-only staff can't
 * read" to someone who can, without any external API or model.
 */
public final class LanguageHintDetector {

    private LanguageHintDetector() {}

    public static String detect(String text) {
        if (text == null || text.isBlank()) return "en";

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);

            if (block == Character.UnicodeBlock.DEVANAGARI) return "hi"; // Hindi/Marathi/etc share this script
            if (block == Character.UnicodeBlock.BENGALI) return "bn";
            if (block == Character.UnicodeBlock.TAMIL) return "ta";
            if (block == Character.UnicodeBlock.TELUGU) return "te";
            if (block == Character.UnicodeBlock.KANNADA) return "kn";
            if (block == Character.UnicodeBlock.MALAYALAM) return "ml";
            if (block == Character.UnicodeBlock.GUJARATI) return "gu";
            if (block == Character.UnicodeBlock.GURMUKHI) return "pa";
            if (block == Character.UnicodeBlock.ORIYA) return "or";
        }
        return "en";
    }
}
