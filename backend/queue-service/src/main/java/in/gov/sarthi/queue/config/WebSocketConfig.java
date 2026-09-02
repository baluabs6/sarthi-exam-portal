package in.gov.sarthi.queue.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Exposes a STOMP endpoint at /ws (SockJS-wrapped so it degrades to
 * long-polling on networks/proxies that block raw WebSocket — common on
 * institutional and mobile-carrier networks in India). Every waiting
 * user subscribes to their own /topic/queue/{queueId} so updates are
 * targeted rather than broadcast to everyone.
 *
 * Allowed origins are now a configured allowlist rather than "*" — a
 * wildcard here would let any external site open a WebSocket to this
 * service on a visitor's behalf.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${security.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins.split(","))
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
