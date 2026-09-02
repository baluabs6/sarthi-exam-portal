package in.gov.sarthi.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * WhatsApp is the primary channel: in India it has by far the highest
 * open/delivery rate and near-zero telecom-network congestion delays
 * compared to SMS routes during high-traffic windows (everyone's OTP/SMS
 * competing for the same DLT-registered routes at once).
 *
 * Wired to the real WhatsApp Business Cloud API in production; here it
 * logs the send so the flow is fully runnable without external
 * credentials.
 */
@Component
public class WhatsAppBusinessProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppBusinessProvider.class);

    @Override
    public String getName() { return "whatsapp-business-api"; }

    @Override
    public void send(String rollNumber, String message) {
        log.info("[WhatsApp -> {}] {}", rollNumber, message);
    }
}
