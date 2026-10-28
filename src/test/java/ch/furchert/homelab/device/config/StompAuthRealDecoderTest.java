package ch.furchert.homelab.device.config;

import ch.furchert.homelab.device.controller.DeviceController;
import ch.furchert.homelab.device.service.DeviceService;
import ch.furchert.homelab.device.service.MqttClientService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StompAuthChannelInterceptor} on STOMP CONNECT with the production {@link JwtDecoder}
 * (auto-configured from {@code SecurityConfig}, not mocked) against a JWKS served by the test.
 * Tokens are real RS256 signatures; the harness mirrors {@code AccessTokenTypeGateTest}.
 *
 * <p>Covers what the mocked decoder in the WebSocket integration tests cannot: an expired token,
 * an access token with {@code typ} {@code at+jwt}, and a token signed by a foreign key are all
 * rejected on CONNECT. Issuer and audience are not validated by this service yet; those checks
 * belong to #81 and will apply here automatically because CONNECT uses the same decoder.
 */
@WebMvcTest(DeviceController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "DEVICE_SERVICE_CLIENT_SECRET=test")
class StompAuthRealDecoderTest {

    private static final String KEY_ID = "stomp-test-key";
    private static final RSAKey SIGNING_KEY = generateKey();
    /** Same key id, different key pair: models a token minted by someone else. */
    private static final RSAKey FOREIGN_KEY = generateKey();
    private static final HttpServer JWKS_SERVER = startJwksServer(SIGNING_KEY);

    @Autowired
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private MqttClientService mqttClientService;

    private final MessageChannel channel = Mockito.mock(MessageChannel.class);

    @DynamicPropertySource
    static void jwkSetUri(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/oauth2/jwks");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Test
    void connect_withValidToken_authenticatesTheSession() throws Exception {
        assertThat(jwtDecoder).isInstanceOf(NimbusJwtDecoder.class);
        assertThat(Mockito.mockingDetails(jwtDecoder).isMock()).isFalse();

        Message<?> result = interceptor().preSend(connect(token(SIGNING_KEY, null, Instant.now().plusSeconds(300))), channel);

        assertThat(MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class).getUser())
                .isInstanceOfSatisfying(JwtAuthenticationToken.class,
                        user -> assertThat(user.getToken().getSubject()).isEqualTo("owner"));
    }

    @Test
    void connect_withExpiredToken_isRejected() throws Exception {
        // Well past the decoder's default 60 s clock skew.
        String expired = token(SIGNING_KEY, null, Instant.now().minusSeconds(3600));

        assertRejected(expired);
    }

    @ParameterizedTest
    @ValueSource(strings = {"at+jwt", "application/at+jwt"})
    void connect_withAccessTokenType_isRejected(String typ) throws Exception {
        assertRejected(token(SIGNING_KEY, typ, Instant.now().plusSeconds(300)));
    }

    @Test
    void connect_withTokenSignedByForeignKey_isRejected() throws Exception {
        assertRejected(token(FOREIGN_KEY, null, Instant.now().plusSeconds(300)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertRejected(String token) {
        assertThatThrownBy(() -> interceptor().preSend(connect(token), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("Unauthorized")
                .hasNoCause();
    }

    private StompAuthChannelInterceptor interceptor() {
        return new StompAuthChannelInterceptor(jwtDecoder, true);
    }

    private static Message<byte[]> connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static String token(RSAKey key, String typ, Instant expiresAt) throws JOSEException {
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID);
        if (typ != null) {
            header.type(new JOSEObjectType(typ));
        }
        Instant issuedAt = expiresAt.minusSeconds(600);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://auth.furchert.ch")
                .subject("owner")
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(header.build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksServer(RSAKey key) {
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/oauth2/jwks", exchange -> {
                exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
