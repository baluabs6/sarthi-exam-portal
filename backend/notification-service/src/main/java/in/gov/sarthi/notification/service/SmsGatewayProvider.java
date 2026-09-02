package in.gov.sarthi.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fallback channel used only when WhatsApp delivery fails (e.g. the
 * number isn't WhatsApp-registered, or the Cloud API itself is briefly
 * down) — this is the "multiple SMS gateway providers with automatic
 * failover" pattern in practice.
 */
@Component
public class SmsGatewayProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SmsGatewayProvider.class);

    @Override
    public String getName() { return "sms-gateway-dlt"; }

    @Override
    public void send(String rollNumber, String message) {
        log.info("[SMS -> {}] {}", rollNumber, message);
    }
}
