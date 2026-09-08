package in.gov.sarthi.assistant.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Deliberately minimal compared to queue-service/result-service's full
 * AdminAccountRegistry + role enforcement + lockout tracker: this
 * service has exactly one admin action (cluster labeling), it's a
 * read-enrichment of data an admin can already see, and every role
 * (including AUDITOR) is allowed to trigger it. Full role separation
 * and brute-force lockout live where the actual admin actions with
 * consequences are — queue-service and result-service.
 *
 * Still reads from the SAME env vars (ADMIN_API_KEY / ADMIN_ACCOUNTS_JSON)
 * so an admin doesn't need a fourth credential.
 */
@Component
public class AdminKeyGate {

    private final Set<String> validKeys = new HashSet<>();

    public AdminKeyGate(
            @Value("${assistant.admin-api-key:}") String legacyKey,
            @Value("${assistant.admin-accounts-json:}") String accountsJson) {

        if (legacyKey != null && !legacyKey.isBlank()) {
            validKeys.add(legacyKey);
        }
        if (accountsJson != null && !accountsJson.isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode array = mapper.readTree(accountsJson);
                for (JsonNode node : array) {
                    if (node.has("key")) {
                        validKeys.add(node.get("key").asText());
                    }
                }
            } catch (Exception ignored) {
                // Falls back to whatever legacyKey provided above.
            }
        }
    }

    public boolean isValid(String providedKey) {
        return providedKey != null && validKeys.contains(providedKey);
    }
}
