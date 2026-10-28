package ch.furchert.homelab.device.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Guards the client-inbound STOMP channel.
 *
 * <ul>
 *   <li>{@code CONNECT}: when {@code requireAuth} is set, the frame must carry a native
 *       {@code Authorization: Bearer <jwt>} header that the resource server's {@link JwtDecoder}
 *       accepts. Browsers cannot set headers on the WebSocket upgrade, so this is where
 *       authentication happens; {@code /ws/**} itself stays {@code permitAll()}.</li>
 *   <li>{@code SUBSCRIBE}: when {@code requireAuth} is set, the session must have an authenticated
 *       user (normally unreachable, because a rejected CONNECT closes the session). In both modes the
 *       destination must lie under {@code /topic/terrarium/}, the only tree the service broadcasts to.</li>
 *   <li>{@code SEND}: only application destinations ({@code /app/**}) are accepted, regardless of
 *       {@code requireAuth}. Broker destinations such as {@code /topic/**} are server-only; the
 *       simple broker would otherwise relay a client frame to every subscriber.</li>
 *   <li>Server-to-client commands ({@code MESSAGE}, {@code CONNECTED}, {@code RECEIPT},
 *       {@code ERROR}) are rejected when a client sends them; the simple broker would relay a
 *       client {@code MESSAGE} frame just like a {@code SEND}.</li>
 * </ul>
 *
 * <p>Rejections throw {@link MessageDeliveryException} with a fixed message: Spring sends it to the
 * client in an ERROR frame and closes the session, and logs rejected SEND frames at ERROR level, so
 * the message must never contain the token, decoder details or the client-supplied destination.
 */
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String APP_DESTINATION_PREFIX = "/app/";
    private static final String SUBSCRIBE_DESTINATION_PREFIX = "/topic/terrarium/";

    private final JwtDecoder jwtDecoder;
    private final boolean requireAuth;

    public StompAuthChannelInterceptor(JwtDecoder jwtDecoder, boolean requireAuth) {
        this.jwtDecoder = jwtDecoder;
        this.requireAuth = requireAuth;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT, STOMP -> {
                if (requireAuth) {
                    accessor.setUser(new JwtAuthenticationToken(authenticate(message, accessor)));
                }
            }
            case SUBSCRIBE -> {
                if (requireAuth && accessor.getUser() == null) {
                    throw new MessageDeliveryException(message, "Unauthorized");
                }
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith(SUBSCRIBE_DESTINATION_PREFIX)) {
                    throw new MessageDeliveryException(message, "Forbidden destination");
                }
            }
            case SEND -> {
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith(APP_DESTINATION_PREFIX)) {
                    throw new MessageDeliveryException(message, "Forbidden destination");
                }
            }
            case UNSUBSCRIBE, ACK, NACK, BEGIN, COMMIT, ABORT, DISCONNECT -> {
                // Client commands that cannot publish or read anything: no checks needed
            }
            default -> {
                // Server-to-client frames (MESSAGE, CONNECTED, RECEIPT, ERROR). The simple broker
                // would relay a client MESSAGE to every subscriber, bypassing the SEND rule.
                throw new MessageDeliveryException(message, "Forbidden command");
            }
        }
        return message;
    }

    private Jwt authenticate(Message<?> message, StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new MessageDeliveryException(message, "Unauthorized");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new MessageDeliveryException(message, "Unauthorized");
        }
        try {
            return jwtDecoder.decode(token);
        } catch (JwtException e) {
            // Deliberately drop the cause: its message can echo token content into the ERROR frame or logs.
            throw new MessageDeliveryException(message, "Unauthorized");
        }
    }
}
