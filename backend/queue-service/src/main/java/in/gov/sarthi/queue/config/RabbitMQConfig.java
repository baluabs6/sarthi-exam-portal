package in.gov.sarthi.queue.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "queue.events" topic exchange carries two kinds of events, routed by key:
 *   - "admitted"  -> consumed by notification-service (push SMS/WhatsApp,
 *                    tell the browser it's their turn)
 *   - "audit"     -> consumed by notification-service and written to
 *                    MongoDB as an immutable event log (useful for
 *                    disputes: "the portal said I was admitted at 10:42
 *                    but showed an error" is answerable from this log).
 *
 * Using a message queue here — rather than services calling each other
 * directly — means result-service and notification-service can be slow,
 * restarting, or briefly down without queue-service ever blocking on
 * them. Events simply wait in the queue until a consumer is ready.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "queue.events";
    public static final String ADMITTED_QUEUE = "queue.admitted";
    public static final String AUDIT_QUEUE = "queue.audit";
    public static final String ADMITTED_ROUTING_KEY = "admitted";
    public static final String AUDIT_ROUTING_KEY = "audit";

    @Bean
    public TopicExchange queueEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue admittedQueue() {
        return QueueBuilder.durable(ADMITTED_QUEUE).build();
    }

    @Bean
    public Queue auditQueue() {
        return QueueBuilder.durable(AUDIT_QUEUE).build();
    }

    @Bean
    public Binding admittedBinding(Queue admittedQueue, TopicExchange queueEventsExchange) {
        return BindingBuilder.bind(admittedQueue).to(queueEventsExchange).with(ADMITTED_ROUTING_KEY);
    }

    @Bean
    public Binding auditBinding(Queue auditQueue, TopicExchange queueEventsExchange) {
        return BindingBuilder.bind(auditQueue).to(queueEventsExchange).with(AUDIT_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
