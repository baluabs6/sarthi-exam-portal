package in.gov.sarthi.result.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Extractive summarization: scores each sentence in a message by word
 * frequency (a classic, simple technique — TextRank-lite without the
 * graph part) and returns the top-scoring sentence(s) verbatim.
 *
 * Deliberately not generative: this never produces a word that wasn't
 * already in the candidate's own message. For a grievance list view
 * where staff are triaging dozens of tickets, showing "the sentence
 * that best represents this message" is a real, honest time-saver —
 * unlike a generated summary, it can't misrepresent what was actually
 * said, since it IS what was said.
 */
public final class ExtractiveSummarizer {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern WORD_SPLIT = Pattern.compile("\\W+");

    private ExtractiveSummarizer() {}

    public static String summarize(String text, int maxSentences) {
        if (text == null || text.isBlank()) return "";

        String[] sentences = SENTENCE_SPLIT.split(text.trim());
        if (sentences.length <= maxSentences) return text.trim();

        Map<String, Integer> wordFrequency = new HashMap<>();
        for (String word : WORD_SPLIT.split(text.toLowerCase())) {
            if (word.length() > 2) { // skip tiny/stopword-ish tokens cheaply
                wordFrequency.merge(word, 1, Integer::sum);
            }
        }

        Map<String, Double> sentenceScores = new HashMap<>();
        for (String sentence : sentences) {
            String[] words = WORD_SPLIT.split(sentence.toLowerCase());
            double score = Arrays.stream(words)
                    .mapToInt(w -> wordFrequency.getOrDefault(w, 0))
                    .sum();
            sentenceScores.put(sentence, words.length == 0 ? 0 : score / words.length);
        }

        return Arrays.stream(sentences)
                .sorted(Comparator.comparingDouble((String s) -> sentenceScores.getOrDefault(s, 0.0)).reversed())
                .limit(maxSentences)
                // Re-sort selected sentences back into their original
                // reading order, rather than "highest score first",
                // since that reads far more naturally.
                .sorted(Comparator.comparingInt(s -> indexOf(sentences, s)))
                .reduce((a, b) -> a + " " + b)
                .orElse(text.trim());
    }

    private static int indexOf(String[] array, String value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) return i;
        }
        return Integer.MAX_VALUE;
    }
}
