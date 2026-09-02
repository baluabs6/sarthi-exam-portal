package in.gov.sarthi.queue.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Caps request body size at the servlet layer, before Jackson ever tries
 * to deserialize it. Without this, a large-payload POST to /api/grievances
 * (a 2000-char message field, but no upstream cap on the raw body) could
 * be used to waste server resources parsing oversized JSON.
 */
@Component
public class RequestSizeLimitFilter implements Filter {

    private static final long MAX_BODY_BYTES = 64 * 1024; // 64KB — generous for any JSON this API accepts

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        long contentLength = httpRequest.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            httpResponse.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"message\":\"Request body too large\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
