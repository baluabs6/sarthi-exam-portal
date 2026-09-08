package in.gov.sarthi.assistant.faq;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Retrieval half of the RAG pipeline: scores every FAQ document against
 * the candidate's question using TF-IDF + cosine similarity, and returns
 * the top-k matches to ground the LLM's answer in.
 *
 * Deliberately lexical (no embeddings/vector store) rather than a hard
 * requirement of "real" RAG: at this corpus size (a few dozen curated
 * entries, changing rarely), TF-IDF gives good-enough retrieval without
 * a second external API/model dependency, a vector database, or an
 * embedding-refresh job. If the FAQ corpus grows into the hundreds/
 * thousands of documents, swap this class for a pgvector-backed
 * retriever (Postgres is already in this stack) — every other part of
 * the pipeline (AssistantService, the prompt template, the grounding
 * check) stays the same.
 */
@Component
public class FaqRetriever {

    private static final Pattern WORD_SPLIT = Pattern.compile("\\W+");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "do", "does", "i", "my", "to", "of", "in", "on",
            "for", "and", "or", "what", "how", "why", "will", "can", "it", "if", "this", "that");

    private final FaqCatalog catalog;
    private final Map<String, Map<String, Double>> documentVectors = new LinkedHashMap<>();
    private final Map<String, Double> idf = new HashMap<>();

    public FaqRetriever(FaqCatalog catalog) {
        this.catalog = catalog;
        buildIndex();
    }

    private void buildIndex() {
        List<FaqDocument> docs = catalog.all();
        List<Map<String, Integer>> termCounts = new ArrayList<>();

        for (FaqDocument doc : docs) {
            String text = doc.question() + " " + String.join(" ", doc.keywords());
            termCounts.add(termFrequency(text));
        }

        Map<String, Integer> docFrequency = new HashMap<>();
        for (Map<String, Integer> tf : termCounts) {
            for (String term : tf.keySet()) {
                docFrequency.merge(term, 1, Integer::sum);
            }
        }
        int totalDocs = Math.max(docs.size(), 1);
        for (Map.Entry<String, Integer> e : docFrequency.entrySet()) {
            idf.put(e.getKey(), Math.log((1.0 + totalDocs) / (1.0 + e.getValue())) + 1.0);
        }

        for (int i = 0; i < docs.size(); i++) {
            documentVectors.put(docs.get(i).id(), tfIdfVector(termCounts.get(i)));
        }
    }

    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String token : WORD_SPLIT.split(text.toLowerCase())) {
            if (token.length() > 1 && !STOPWORDS.contains(token)) {
                tf.merge(token, 1, Integer::sum);
            }
        }
        return tf;
    }

    private Map<String, Double> tfIdfVector(Map<String, Integer> tf) {
        Map<String, Double> vector = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            vector.put(e.getKey(), e.getValue() * idf.getOrDefault(e.getKey(), 1.0));
        }
        return vector;
    }

    private double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, normA = 0, normB = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            dot += e.getValue() * b.getOrDefault(e.getKey(), 0.0);
            normA += e.getValue() * e.getValue();
        }
        for (double v : b.values()) normB += v * v;
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record ScoredFaq(FaqDocument document, double score) {}

    /** Top-k FAQ documents relevant to the query, above a minimum-relevance floor. */
    public List<ScoredFaq> retrieve(String query, int topK) {
        Map<String, Double> queryVector = tfIdfVector(termFrequency(query));
        List<FaqDocument> docs = catalog.all();

        return docs.stream()
                .map(doc -> new ScoredFaq(doc, cosineSimilarity(queryVector, documentVectors.get(doc.id()))))
                .filter(sf -> sf.score() > 0.05) // floor: don't force-feed irrelevant context to the model
                .sorted(Comparator.comparingDouble(ScoredFaq::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }
}
