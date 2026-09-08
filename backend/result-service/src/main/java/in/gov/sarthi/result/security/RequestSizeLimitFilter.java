package in.gov.sarthi.result.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Caps request body size at the servlet layer, before Jackson ever tries
 * to deserialize it. Without this, a large-payload POST to /api/grievances
 * (a 2000-char message field, but no upstream cap on the raw body) could
 * be used to waste server resources parsing oversized JSON.
 *
 * SECURITY: this used to only check getContentLengthLong() — the
 * client-DECLARED size. That's not enforcement, it's trusting the
 * client's word: a request using chunked transfer-encoding has no
 * Content-Length header at all (getContentLengthLong() returns -1, so
 * the old check silently let it through unbounded), and even with a
 * Content-Length present, nothing stops a client from sending more bytes
 * than it declared. This now enforces the cap against the actual bytes
 * read, by wrapping the input stream in one that aborts once the limit
 * is crossed, regardless of what the client claimed upfront.
 *
 * The wrapper commits the 413 response itself (rather than only
 * throwing and relying on the exception unwinding cleanly back to this
 * filter's try/catch) because Spring Boot's own ErrorPageFilter can sit
 * between this filter and the dispatcher and may intercept an
 * unchecked exception before it reaches here — committing the response
 * directly is correct regardless of exactly where in the chain that
 * happens.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // as early as practical, but see the class comment on why the fix doesn't depend on this
public class RequestSizeLimitFilter implements Filter {

    static final int MAX_BODY_BYTES = 64 * 1024; // 64KB — generous for any JSON this API accepts

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Fast-path rejection when the client is honest about a too-large
        // body — avoids reading anything at all in the common case.
        long declaredLength = httpRequest.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            reject(httpResponse);
            return;
        }

        try {
            chain.doFilter(new SizeCappedRequestWrapper(httpRequest, httpResponse), response);
        } catch (RequestTooLargeException e) {
            // Already committed by the wrapper below; nothing further to do.
        }
    }

    private static void reject(HttpServletResponse httpResponse) throws IOException {
        if (httpResponse.isCommitted()) return;
        httpResponse.reset();
        httpResponse.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        httpResponse.setContentType("application/json");
        httpResponse.getWriter().write("{\"message\":\"Request body too large\"}");
        httpResponse.flushBuffer();
    }

    private static class RequestTooLargeException extends RuntimeException {}

    /** Wraps the raw input stream so reading past MAX_BODY_BYTES aborts and commits a 413, no matter what Content-Length claimed. */
    private static class SizeCappedRequestWrapper extends HttpServletRequestWrapper {
        private final HttpServletResponse response;

        SizeCappedRequestWrapper(HttpServletRequest request, HttpServletResponse response) {
            super(request);
            this.response = response;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
            jakarta.servlet.ServletInputStream original = super.getInputStream();
            return new jakarta.servlet.ServletInputStream() {
                private long bytesRead = 0;

                private void checkLimit(long n) throws IOException {
                    if (n <= 0) return;
                    bytesRead += n;
                    if (bytesRead > MAX_BODY_BYTES) {
                        reject(response);
                        throw new RequestTooLargeException();
                    }
                }

                @Override
                public int read() throws IOException {
                    int b = original.read();
                    checkLimit(b < 0 ? 0 : 1);
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = original.read(b, off, len);
                    checkLimit(Math.max(n, 0));
                    return n;
                }

                @Override
                public boolean isFinished() { return original.isFinished(); }

                @Override
                public boolean isReady() { return original.isReady(); }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    original.setReadListener(readListener);
                }
            };
        }
    }
}

