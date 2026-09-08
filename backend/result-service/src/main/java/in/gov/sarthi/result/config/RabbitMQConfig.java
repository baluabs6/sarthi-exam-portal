package in.gov.sarthi.result.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String ADMITTED_QUEUE = "queue.admitted";

    // Redeclaring a durable queue with identical properties is a no-op in
    // RabbitMQ, so it's safe for both queue-service and result-service to
    // declare it — whichever starts first "wins" and the other just
    // confirms it matches.
    @Bean
    public Queue admittedQueue() {
        return QueueBuilder.durable(ADMITTED_QUEUE).build();
    }

    // SECURITY/CORRECTNESS: queue-service serializes AMQP messages as
    // JSON (see its own RabbitMQConfig's Jackson2JsonMessageConverter
    // bean) — but a MessageConverter is per-Spring-context, not
    // broker-wide, so THIS service also needs its own instance of the
    // same converter or its @RabbitListener(s) expecting
    // Map<String,String> would fail to deserialize every incoming
    // message (the default SimpleMessageConverter doesn't do JSON, so
    // this was silently breaking AdmissionEventListener's cache
    // pre-warming on every single admission event).
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ---------------------------------------------------------------
    // Grievance resolution notifications — previously the ONLY way a
    // candidate could learn their grievance was resolved was either
    // manually revisiting the Track page, or supplying a webhookUrl at
    // submission time (a developer-facing concept most candidates would
    // never use). This reuses the same event-driven pattern
    // queue-service already uses for admission notifications: publish
    // here, let notification-service (which already owns the
    // WhatsApp->SMS fallback chain) pick it up and actually deliver it.
    // ---------------------------------------------------------------

    public static final String EXCHANGE = "result.events";
    public static final String GRIEVANCE_RESOLVED_QUEUE = "result.grievance-resolved";
    public static final String GRIEVANCE_RESOLVED_ROUTING_KEY = "grievance.resolved";

    @Bean
    public TopicExchange resultEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue grievanceResolvedQueue() {
        return QueueBuilder.durable(GRIEVANCE_RESOLVED_QUEUE).build();
    }

    @Bean
    public Binding grievanceResolvedBinding(Queue grievanceResolvedQueue, TopicExchange resultEventsExchange) {
        return BindingBuilder.bind(grievanceResolvedQueue).to(resultEventsExchange).with(GRIEVANCE_RESOLVED_ROUTING_KEY);
    }
}
