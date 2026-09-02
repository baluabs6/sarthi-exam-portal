package in.gov.sarthi.queue.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Admin gate with real per-account roles (see AdminAccount,
 * AdminAccountRegistry) — a genuine upgrade from a single shared secret
 * everyone used identically.
 *
 * Unlike result-service (where the whole admin surface is grievance
 * management, so writes are SUPER_ADMIN only), queue-service's write
 * surface is just the live admission-rate tuning — a routine
 * operational action during a result-day event — so QUEUE_OPERATOR is
 * also allowed to write here, not just SUPER_ADMIN. AUDITOR remains
 * read-only in both services.
 *
 * Repeated wrong keys from the same IP are throttled via
 * AdminLockoutTracker, closing the gap where a wrong key just got a 401
 * with no limit on retries.
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String REQUEST_ATTR_ACCOUNT = "sarthi.adminAccount";

    private static final Set<String> WRITE_METHODS = Set.of("PATCH", "POST", "PUT", "DELETE");
    private static final Set<AdminAccount.AdminRole> ALLOWED_TO_WRITE =
            Set.of(AdminAccount.AdminRole.SUPER_ADMIN, AdminAccount.AdminRole.QUEUE_OPERATOR);

    private final AdminAccountRegistry accountRegistry;
    private final AdminLockoutTracker lockoutTracker;

    public AdminAuthInterceptor(AdminAccountRegistry accountRegistry, AdminLockoutTracker lockoutTracker) {
        this.accountRegistry = accountRegistry;
        this.lockoutTracker = lockoutTracker;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = clientIp(request);

        if (lockoutTracker.isLockedOut(clientIp)) {
            reject(response, HttpServletResponse.SC_TOO_MANY_REQUESTS,
                    "Too many failed admin attempts. Try again later.");
            return false;
        }

        String provided = request.getHeader("X-Admin-Key");
        var account = accountRegistry.findByKey(provided);
        if (account.isEmpty()) {
            lockoutTracker.recordFailure(clientIp);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Admin key missing or invalid");
            return false;
        }
        lockoutTracker.recordSuccess(clientIp);

        boolean isWrite = WRITE_METHODS.contains(request.getMethod());
        if (isWrite && !ALLOWED_TO_WRITE.contains(account.get().role())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "Your admin role (" + account.get().role() + ") does not permit making changes here.");
            return false;
        }

        request.setAttribute(REQUEST_ATTR_ACCOUNT, account.get());
        return true;
    }

    public static String actorName(HttpServletRequest request) {
        Object attr = request.getAttribute(REQUEST_ATTR_ACCOUNT);
        return attr instanceof AdminAccount account ? account.name() : "unknown";
    }

    private void reject(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
