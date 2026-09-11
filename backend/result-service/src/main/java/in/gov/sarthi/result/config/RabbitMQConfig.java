package in.gov.sarthi.result.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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
}
