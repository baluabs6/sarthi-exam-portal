package in.gov.sarthi.notification.listener;

import in.gov.sarthi.notification.service.NotificationDispatchService;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.Map;

@Configuration
class GrievanceEventDeclarations {

    // Mirrors result-service's RabbitMQConfig exactly (exchange name,
    // queue name, routing key) — idempotent redeclaration, same reasoning
    // as the admitted-queue pattern elsewhere in this service.
    static final String EXCHANGE = "result.events";
    static final String QUEUE = "result.grievance-resolved";
    static final String ROUTING_KEY = "grievance.resolved";

    @Bean
    public TopicExchange resultEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue grievanceResolvedQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding grievanceResolvedBinding(Queue grievanceResolvedQueue, TopicExchange resultEventsExchange) {
        return BindingBuilder.bind(grievanceResolvedQueue).to(resultEventsExchange).with(ROUTING_KEY);
    }
}

/**
 * Previously the only way a candidate learned their grievance was
 * resolved was manually revisiting the Track page, or supplying a
 * webhookUrl at submission time (a developer-facing concept most
 * candidates would never use). This reuses the exact same delivery
 * chain (WhatsApp, falling back to SMS) already built for admission
 * notifications — see NotificationDispatchService.
 */
@Component
public class GrievanceResolutionListener {

    private final NotificationDispatchService dispatchService;

    public GrievanceResolutionListener(NotificationDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @RabbitListener(queues = GrievanceEventDeclarations.QUEUE)
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void onGrievanceResolved(Map<String, String> event) {
        String rollNumber = event.get("rollNumber");
        String ticketRef = event.get("ticketRef");
        String status = event.get("status");

        String statusText = "RESOLVED".equals(status) ? "has been resolved" : "has been reviewed and closed";
        String message = "Update on your grievance " + ticketRef + ": it " + statusText
                + ". Check the Track page on the portal for details.";

        dispatchService.dispatch(rollNumber, message);
    }
}
