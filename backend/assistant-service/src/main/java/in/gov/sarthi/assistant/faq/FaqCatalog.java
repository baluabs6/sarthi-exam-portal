package in.gov.sarthi.assistant.faq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Loads the FAQ corpus once at startup from the bundled JSON resource.
 * Deliberately a static, versioned file rather than a database table for
 * now — it's small, hand-curated, and reviewed content (same spirit as
 * the frontend's faqData.js it mirrors). If this grows into something
 * bigger/editable-by-admins, the natural next step is a Postgres table
 * plus an admin CRUD screen, with FaqRetriever's interface unchanged.
 */
@Component
public class FaqCatalog {

    private final List<FaqDocument> documents;

    public FaqCatalog() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/faq-catalog.json")) {
            documents = List.of(mapper.readValue(in, FaqDocument[].class));
        }
    }

    public List<FaqDocument> all() {
        return documents;
    }
}
