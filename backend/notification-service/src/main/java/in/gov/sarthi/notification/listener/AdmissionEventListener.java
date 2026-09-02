package in.gov.sarthi.notification.listener;

import in.gov.sarthi.notification.service.NotificationDispatchService;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.Map;

@Configuration
class QueueDeclarations {

    // Idempotent re-declaration, same reasoning as in result-service: this
    // service may start before or after queue-service and either order
    // must work.
    @Bean
    public Queue admittedQueue() {
        return QueueBuilder.durable("queue.admitted").build();
    }
}

/**
 * Every "admitted" event triggers exactly one outbound notification.
 * Consuming this off a message queue — rather than queue-service calling
 * an SMS API directly — means a slow or briefly-down notification
 * provider can never delay someone's admission from the queue; the
 * event just waits here until this service (or the provider) recovers.
 */
@Component
public class AdmissionEventListener {

    private final NotificationDispatchService dispatchService;

    public AdmissionEventListener(NotificationDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @RabbitListener(queues = "queue.admitted")
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void onAdmitted(Map<String, String> event) {
        String rollNumber = event.get("rollNumber");
        String examId = event.get("examId");
        String message = "Your turn is here! Results for " + examId + " (Roll No. " + rollNumber + ") are ready to view now.";
        dispatchService.dispatch(rollNumber, message);
    }
}
