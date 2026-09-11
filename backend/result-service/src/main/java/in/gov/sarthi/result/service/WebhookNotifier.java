package in.gov.sarthi.result.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Delivers a "your grievance status changed" notification to a
 * candidate-supplied callback URL — the webhook-notifications feature.
 *
 * Accepting an arbitrary URL from user input and then having the
 * server make an HTTP request to it is a textbook SSRF vector: without
 * checks, a malicious "webhookUrl" could point at
 * http://169.254.169.254/ (cloud metadata endpoints), an internal
 * admin panel, or any other address only reachable from inside your own
 * network — and the server would dutifully make that request on the
 * attacker's behalf. {@link #isSafeUrl} is the actual defense: it
 * resolves the hostname and rejects loopback, link-local, and private
 * (RFC 1918) address ranges before any request is attempted, and only
 * allows http/https. This is a meaningful mitigation, not a complete
 * one — DNS rebinding after the check but before the request is a known
 * residual risk that a production system would close with an
 * allowlist-only policy or a dedicated egress proxy.
 */
@Service
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final RestTemplate restTemplate;
    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);

    public WebhookNotifier() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.getRequestFactory();
    }

    public boolean isSafeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return false;
            if (uri.getHost() == null) return false;

            InetAddress addr = InetAddress.getByName(uri.getHost());
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                return false;
            }
            // Blocks the common cloud-metadata SSRF target explicitly,
            // since it isn't always caught by isSiteLocalAddress() on
            // every platform.
            if (addr.getHostAddress().equals("169.254.169.254")) return false;

            return true;
        } catch (Exception e) {
            return false; // any resolution failure / malformed URL is treated as unsafe, not retried
        }
    }

    /**
     * Fire-and-forget on a small dedicated pool — a slow/unreachable
     * webhook endpoint must never block the admin action (grievance
     * status update) that triggered it.
     */
    public void notifyAsync(String webhookUrl, String ticketRef, String status) {
        if (!isSafeUrl(webhookUrl)) {
            log.warn("Skipped webhook delivery for {} — URL failed safety validation", ticketRef);
            return;
        }
        executor.submit(() -> {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                Map<String, String> payload = Map.of("ticketRef", ticketRef, "status", status);
                restTemplate.postForEntity(webhookUrl, new HttpEntity<>(payload, headers), Void.class);
            } catch (Exception e) {
                // Best-effort — a webhook failing must never surface as
                // an error to the admin performing the status update.
                log.warn("Webhook delivery failed for {}: {}", ticketRef, e.getMessage());
            }
        });
    }
}
