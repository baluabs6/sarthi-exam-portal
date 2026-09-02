package in.gov.sarthi.queue.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses ADMIN_ACCOUNTS_JSON — a JSON array like:
 * {@code [{"key":"...","name":"Priya (Ops)","role":"QUEUE_OPERATOR"}]}
 * — into named, roled admin accounts.
 *
 * Falls back to a single SUPER_ADMIN account built from the legacy
 * admin.api-key property if ADMIN_ACCOUNTS_JSON isn't set, so existing
 * deployments using just one shared key keep working unchanged.
 */
@Component
public class AdminAccountRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountRegistry.class);

    private final Map<String, AdminAccount> accountsByKey = new HashMap<>();

    public AdminAccountRegistry(
            @Value("${admin.api-key}") String legacyApiKey,
            @Value("${admin.accounts-json:}") String accountsJson) {

        if (accountsJson != null && !accountsJson.isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode array = mapper.readTree(accountsJson);
                for (JsonNode node : array) {
                    String key = node.get("key").asText();
                    String name = node.has("name") ? node.get("name").asText() : "unnamed";
                    AdminAccount.AdminRole role = AdminAccount.AdminRole.valueOf(node.get("role").asText());
                    accountsByKey.put(key, new AdminAccount(key, name, role));
                }
                log.info("Loaded {} admin account(s) from ADMIN_ACCOUNTS_JSON", accountsByKey.size());
            } catch (Exception e) {
                log.error("Failed to parse ADMIN_ACCOUNTS_JSON — falling back to the single legacy admin key", e);
                accountsByKey.clear();
            }
        }

        if (accountsByKey.isEmpty()) {
            accountsByKey.put(legacyApiKey, new AdminAccount(legacyApiKey, "default", AdminAccount.AdminRole.SUPER_ADMIN));
        }
    }

    public Optional<AdminAccount> findByKey(String apiKey) {
        if (apiKey == null) return Optional.empty();
        // Constant-time-ish lookup isn't feasible with a HashMap; the
        // per-IP lockout in AdminAuthInterceptor is what actually bounds
        // guessing attempts, not this lookup's timing characteristics.
        return Optional.ofNullable(accountsByKey.get(apiKey));
    }
}
