package in.gov.sarthi.notification.service;

import in.gov.sarthi.notification.model.NotificationLog;
import in.gov.sarthi.notification.model.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    private final WhatsAppBusinessProvider whatsApp;
    private final SmsGatewayProvider sms;
    private final NotificationLogRepository logRepository;

    public NotificationDispatchService(WhatsAppBusinessProvider whatsApp,
                                        SmsGatewayProvider sms,
                                        NotificationLogRepository logRepository) {
        this.whatsApp = whatsApp;
        this.sms = sms;
        this.logRepository = logRepository;
    }

    public void dispatch(String rollNumber, String message) {
        NotificationProvider[] chain = { whatsApp, sms };
        Exception lastError = null;

        for (NotificationProvider provider : chain) {
            try {
                provider.send(rollNumber, message);
                logRepository.save(NotificationLog.delivered(rollNumber, message, provider.getName()));
                return; // first successful channel wins
            } catch (Exception ex) {
                lastError = ex;
                log.warn("Provider {} failed for {}, trying next channel: {}",
                        provider.getName(), rollNumber, ex.getMessage());
            }
        }

        logRepository.save(NotificationLog.failed(rollNumber, message,
                lastError != null ? lastError.getMessage() : "all providers failed"));
    }
}
