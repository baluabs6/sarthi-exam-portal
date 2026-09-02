package in.gov.sarthi.result.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Gate for the partner/institutional bulk API — a coaching institute
 * checking many of its own students' results shouldn't have to send
 * each one through the individual queue-and-ticket flow meant for a
 * single candidate on their own device. This is a *separate* trusted
 * channel with its own key and its own (tighter) rate limit at the
 * gateway, kept completely apart from PARTNER_API_KEY never being
 * usable to reach admin-only endpoints, and admin.api-key never being
 * usable here.
 */
@Component
public class PartnerAuthInterceptor implements HandlerInterceptor {

    private final String partnerApiKey;

    public PartnerAuthInterceptor(@Value("${partner.api-key:}") String partnerApiKey) {
        this.partnerApiKey = partnerApiKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String provided = request.getHeader("X-Partner-Key");
        if (partnerApiKey.isBlank() || provided == null || !constantTimeEquals(provided, partnerApiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Partner key missing or invalid\"}");
            return false;
        }
        return true;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
