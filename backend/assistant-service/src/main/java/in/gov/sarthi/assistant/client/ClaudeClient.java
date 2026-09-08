package in.gov.sarthi.assistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every LLM call in the whole system goes through this one class. It is
 * intentionally the ONLY place that knows about ANTHROPIC_API_KEY.
 *
 * If the key isn't configured, {@link #isEnabled()} returns false and
 * every caller is expected to fall back to a clearly-labeled
 * "AI assistant not configured" response rather than erroring — the
 * rest of the portal (queue, results, grievances) must work identically
 * whether or not this is set up.
 *
 * A short, hard timeout is used deliberately: this service must never
 * hang a candidate-facing request waiting on a third-party API. On top
 * of that, a circuit breaker (see application.yml for thresholds) trips
 * after a run of failures and fails fast for a cooldown window, rather
 * than letting every incoming request pay the full timeout while the
 * provider is down/degraded.
 */
@Component
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeClient(
            @Value("${assistant.anthropic-api-key:}") String apiKey,
            @Value("${assistant.anthropic-model:claude-sonnet-4-6}") String model) {
        this.apiKey = apiKey;
        this.model = model;

        SimpleClientHttpRequestFactoryWithTimeouts factory = new SimpleClientHttpRequestFactoryWithTimeouts();
        this.restTemplate = new RestTemplate(factory);
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param systemPrompt instructions + retrieved context the answer must be grounded in
     * @param userMessage  the candidate/admin-supplied text
     * @return the model's text response, or empty if the call failed/timed out/is disabled/circuit is open
     */
    @CircuitBreaker(name = "claude", fallbackMethod = "completeFallback")
    public Optional<String> complete(String systemPrompt, String userMessage) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", ANTHROPIC_VERSION);

            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 500,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", userMessage))
            );

            ResponseEntity<String> response = restTemplate.postForEntity(
                    API_URL, new HttpEntity<>(body, headers), String.class);

            JsonNode root = mapper.readTree(response.getBody());
            JsonNode content = root.path("content");
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            return text.isEmpty() ? Optional.empty() : Optional.of(text.toString());
        } catch (RestClientException | java.io.IOException e) {
            log.warn("Claude API call failed/timed out — falling back to non-AI behavior: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Invoked by Resilience4j once the circuit is open, instead of even attempting the call. */
    @SuppressWarnings("unused")
    private Optional<String> completeFallback(String systemPrompt, String userMessage, Throwable t) {
        log.warn("Claude circuit breaker open — skipping call: {}", t.getMessage());
        return Optional.empty();
    }

    /** 4s connect / 8s read — generous enough for a short completion, short enough to never hang a request. */
    private static class SimpleClientHttpRequestFactoryWithTimeouts
            extends org.springframework.http.client.SimpleClientHttpRequestFactory {
        SimpleClientHttpRequestFactoryWithTimeouts() {
            setConnectTimeout((int) Duration.ofSeconds(4).toMillis());
            setReadTimeout((int) Duration.ofSeconds(8).toMillis());
        }
    }
}
