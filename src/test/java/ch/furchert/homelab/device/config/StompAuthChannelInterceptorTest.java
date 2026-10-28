package ch.furchert.homelab.device.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StompAuthChannelInterceptorTest {

    private static final String TOKEN = "header.payload.signature";

    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);
    private final MessageChannel channel = mock(MessageChannel.class);

    // ------------------------------------------------------------------
    // CONNECT, authentication required
    // ------------------------------------------------------------------

    @Test
    void connect_withValidBearer_setsJwtUser() {
        Jwt jwt = jwt();
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt);

        Message<?> result = interceptor(true).preSend(
                frame(StompCommand.CONNECT, a -> a.setNativeHeader("Authorization", "Bearer " + TOKEN)), channel);

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(accessor.getUser()).isInstanceOfSatisfying(JwtAuthenticationToken.class,
                user -> assertThat(user.getToken()).isSameAs(jwt));
    }

    @Test
    void connect_acceptsCaseInsensitiveScheme() {
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt());

        Message<?> result = interceptor(true).preSend(
                frame(StompCommand.CONNECT, a -> a.setNativeHeader("Authorization", "bearer " + TOKEN)), channel);

        assertThat(MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class).getUser())
                .isInstanceOf(JwtAuthenticationToken.class);
    }

    @Test
    void stompCommand_isTreatedLikeConnect() {
        assertThatThrownBy(() -> interceptor(true).preSend(frame(StompCommand.STOMP, a -> {}), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connect_withoutAuthorizationHeader_isRejected() {
        assertThatThrownBy(() -> interceptor(true).preSend(frame(StompCommand.CONNECT, a -> {}), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("Unauthorized");
        verifyNoInteractions(jwtDecoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {TOKEN, "Basic dXNlcjpwYXNz", "Bearer", "Bearer ", "Bearer  "})
    void connect_withNonBearerAuthorization_isRejectedWithoutDecoding(String value) {
        assertThatThrownBy(() -> interceptor(true).preSend(
                frame(StompCommand.CONNECT, a -> a.setNativeHeader("Authorization", value)), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("Unauthorized");
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void connect_withTokenTheDecoderRejects_isRejectedWithoutLeakingDetails() {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("signature mismatch for " + TOKEN));

        assertThatThrownBy(() -> interceptor(true).preSend(
                frame(StompCommand.CONNECT, a -> a.setNativeHeader("Authorization", "Bearer " + TOKEN)), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("Unauthorized")
                .hasNoCause();
        verify(jwtDecoder).decode(TOKEN);
    }

    // ------------------------------------------------------------------
    // CONNECT, authentication disabled
    // ------------------------------------------------------------------

    @Test
    void connect_withoutHeader_passesWhenAuthDisabled() {
        Message<?> message = frame(StompCommand.CONNECT, a -> {});

        assertThat(interceptor(false).preSend(message, channel)).isSameAs(message);
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void connect_withHeader_isNotDecodedWhenAuthDisabled() {
        Message<?> message = frame(StompCommand.CONNECT, a -> a.setNativeHeader("Authorization", "Bearer " + TOKEN));

        assertThat(interceptor(false).preSend(message, channel)).isSameAs(message);
        verifyNoInteractions(jwtDecoder);
    }

    // ------------------------------------------------------------------
    // SUBSCRIBE
    // ------------------------------------------------------------------

    @Test
    void subscribe_withoutUser_isRejectedWhenAuthRequired() {
        assertThatThrownBy(() -> interceptor(true).preSend(
                frame(StompCommand.SUBSCRIBE, a -> a.setDestination("/topic/terrarium/terra1")), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("Unauthorized");
    }

    @Test
    void subscribe_withUser_passesWhenAuthRequired() {
        Message<?> message = frame(StompCommand.SUBSCRIBE, a -> {
            a.setDestination("/topic/terrarium/terra1");
            a.setUser(new JwtAuthenticationToken(jwt()));
        });

        assertThat(interceptor(true).preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_withoutUser_passesWhenAuthDisabled() {
        Message<?> message = frame(StompCommand.SUBSCRIBE, a -> a.setDestination("/topic/terrarium/terra1"));

        assertThat(interceptor(false).preSend(message, channel)).isSameAs(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/terrarium", "/topic/terrariumX/terra1", "/topic/other", "/topic/**", "/topic",
            "/app/x", "/user/queue/x", "topic/terrarium/terra1"})
    void subscribe_outsideTerrariumTopics_isRejectedInBothModes(String destination) {
        for (boolean requireAuth : new boolean[] {true, false}) {
            assertThatThrownBy(() -> interceptor(requireAuth).preSend(
                    frame(StompCommand.SUBSCRIBE, a -> {
                        a.setDestination(destination);
                        a.setUser(new JwtAuthenticationToken(jwt()));
                    }), channel))
                    .as("requireAuth=%s, destination=%s", requireAuth, destination)
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessage("Forbidden destination");
        }
    }

    @Test
    void subscribe_withoutDestination_isRejected() {
        assertThatThrownBy(() -> interceptor(false).preSend(frame(StompCommand.SUBSCRIBE, a -> {}), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("Forbidden destination");
    }

    @Test
    void subscribe_toTerrariumWildcard_passes() {
        // Read-only and confined to the terrarium tree, so a pattern subscription is harmless.
        Message<?> message = frame(StompCommand.SUBSCRIBE, a -> {
            a.setDestination("/topic/terrarium/**");
            a.setUser(new JwtAuthenticationToken(jwt()));
        });

        assertThat(interceptor(true).preSend(message, channel)).isSameAs(message);
    }

    // ------------------------------------------------------------------
    // SEND: only application destinations, in both modes
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/topic/terrarium/terra1", "/topic", "/topicX/terra1", "/user/queue/x", "app/x", "/application/x"})
    void send_toNonApplicationDestination_isRejectedInBothModes(String destination) {
        for (boolean requireAuth : new boolean[] {true, false}) {
            assertThatThrownBy(() -> interceptor(requireAuth).preSend(
                    frame(StompCommand.SEND, a -> {
                        a.setDestination(destination);
                        a.setUser(new JwtAuthenticationToken(jwt()));
                    }), channel))
                    .as("requireAuth=%s, destination=%s", requireAuth, destination)
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessage("Forbidden destination");
        }
    }

    @Test
    void send_withoutDestination_isRejected() {
        assertThatThrownBy(() -> interceptor(false).preSend(frame(StompCommand.SEND, a -> {}), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("Forbidden destination");
    }

    @Test
    void send_toApplicationDestination_passes() {
        Message<?> message = frame(StompCommand.SEND, a -> {
            a.setDestination("/app/anything");
            a.setUser(new JwtAuthenticationToken(jwt()));
        });

        assertThat(interceptor(true).preSend(message, channel)).isSameAs(message);
    }

    // ------------------------------------------------------------------
    // Other frames
    // ------------------------------------------------------------------

    @Test
    void disconnect_passesWithoutUser() {
        Message<?> message = frame(StompCommand.DISCONNECT, a -> {});

        assertThat(interceptor(true).preSend(message, channel)).isSameAs(message);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"UNSUBSCRIBE", "ACK", "NACK", "BEGIN", "COMMIT", "ABORT"})
    void otherClientCommands_pass(StompCommand command) {
        Message<?> message = frame(command, a -> a.setUser(new JwtAuthenticationToken(jwt())));

        assertThat(interceptor(true).preSend(message, channel)).isSameAs(message);
    }

    /**
     * Server-to-client frames sent by a client. The simple broker would relay a client
     * {@code MESSAGE} to every subscriber of its destination, bypassing the SEND rule.
     */
    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"MESSAGE", "CONNECTED", "RECEIPT", "ERROR"})
    void serverCommands_fromClient_areRejectedInBothModes(StompCommand command) {
        for (boolean requireAuth : new boolean[] {true, false}) {
            assertThatThrownBy(() -> interceptor(requireAuth).preSend(
                    frame(command, a -> {
                        a.setDestination("/topic/terrarium/terra1");
                        a.setUser(new JwtAuthenticationToken(jwt()));
                    }), channel))
                    .as("requireAuth=%s, command=%s", requireAuth, command)
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessage("Forbidden command");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private StompAuthChannelInterceptor interceptor(boolean requireAuth) {
        return new StompAuthChannelInterceptor(jwtDecoder, requireAuth);
    }

    /** Builds an inbound frame the way {@code StompSubProtocolHandler} does: with a mutable accessor. */
    private static Message<byte[]> frame(StompCommand command, Consumer<StompHeaderAccessor> customizer) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        customizer.accept(accessor);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Jwt jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .subject("owner")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
