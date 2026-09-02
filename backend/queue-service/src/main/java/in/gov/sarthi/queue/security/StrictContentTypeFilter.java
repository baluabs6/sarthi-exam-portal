package in.gov.sarthi.queue.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Rejects POST/PATCH/PUT requests whose Content-Type isn't
 * application/json, before Spring's message converters get involved.
 * Without this, a request crafted with an unexpected content type could
 * probe for content-type confusion issues in the deserialization layer;
 * this closes that off at the door rather than relying on
 * "best-effort" JSON parsing to fail safely on its own.
 */
@Component
public class StrictContentTypeFilter implements Filter {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PATCH", "PUT");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String method = httpRequest.getMethod();
        if (BODY_METHODS.contains(method)) {
            String contentType = httpRequest.getContentType();
            boolean hasBody = httpRequest.getContentLengthLong() > 0;
            if (hasBody && (contentType == null || !contentType.toLowerCase().startsWith("application/json"))) {
                httpResponse.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"message\":\"Content-Type must be application/json\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
