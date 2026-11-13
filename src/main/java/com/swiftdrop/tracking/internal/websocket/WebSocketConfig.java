package com.swiftdrop.tracking.internal.websocket;

import com.swiftdrop.shared.web.ApiPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Live tracking over WebSocket with STOMP (design doc 2.4.10 and 4.5). Two handshake URLs, plain WebSocket (no
 * SockJS):
 * <ul>
 *   <li>{@value #PUBLIC_ENDPOINT}: anyone; may subscribe only to {@code /topic/parcels/{trackingNumber}}.</li>
 *   <li>{@value #STAFF_ENDPOINT}: the handshake goes through the normal security chain, so it needs a valid
 *   {@code SD_TOKEN} cookie; a DISPATCHER or ADMIN may also subscribe to {@code /topic/live}.</li>
 * </ul>
 * In-memory broker: every backend instance gets every parcel event from its own RabbitMQ queue, so each pushes to its
 * own clients. Clients can't send messages; {@link SubscriptionGuard} checks every subscription.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final String PUBLIC_ENDPOINT = ApiPaths.PREFIX + "/ws/public";
    static final String STAFF_ENDPOINT = ApiPaths.PREFIX + "/ws";

    private final String frontendUrl;
    private final SubscriptionGuard guard;
    private final TaskScheduler heartbeatScheduler;

    WebSocketConfig(@Value("${swiftdrop.security.frontend-url}") String frontendUrl, SubscriptionGuard guard,
                    @Lazy TaskScheduler messageBrokerTaskScheduler) {
        this.frontendUrl = frontendUrl;
        this.guard = guard;
        this.heartbeatScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(PUBLIC_ENDPOINT, STAFF_ENDPOINT).setAllowedOrigins(frontendUrl);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {20_000, 20_000})
                .setTaskScheduler(heartbeatScheduler);
        // No @MessageMapping handlers: nothing a client sends is processed.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(guard);
    }
}
