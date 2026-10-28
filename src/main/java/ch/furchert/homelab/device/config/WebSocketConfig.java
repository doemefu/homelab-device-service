package ch.furchert.homelab.device.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket message broker configuration.
 *
 * <p>Clients connect to {@code /ws} and subscribe to topics under {@code /topic}.
 * The in-process simple broker is sufficient for single-node broadcast-only usage.
 *
 * <p>Browser origins are restricted to {@code app.websocket.allowed-origins}. Inbound frames pass
 * {@link StompAuthChannelInterceptor}: a bearer JWT on CONNECT when
 * {@code app.websocket.require-auth} is true, and no client SEND outside {@code /app/**}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtDecoder jwtDecoder;

    @Value("${app.websocket.allowed-origins:https://furchert.ch}")
    private String allowedOrigins;

    @Value("${app.websocket.require-auth:true}")
    private boolean requireAuth;

    public WebSocketConfig(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins.split(","));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthChannelInterceptor(jwtDecoder, requireAuth));
    }
}
