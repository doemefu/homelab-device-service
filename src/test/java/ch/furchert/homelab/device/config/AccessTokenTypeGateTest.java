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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate G5 (doemefu/homelab#168): access tokens with {@code typ} {@code at+jwt} or
 * {@code application/at+jwt} must be rejected with 401 on {@code /devices/**}.
 *
 * <p>Uses the auto-configured decoder against a JWKS served by the test (no mocked decoder).
 * Tokens are real RS256 signatures over a key generated at run time.
 *
 * <p>A change to token validation must update this test in the same pull request (see the
 * ordering constraint in the MCP hub spec, §4.5). A new {@code JwtDecoder} or
 * {@code OAuth2TokenValidator} bean outside {@code SecurityConfig} must be added to
 * {@code @Import}.
 */
@WebMvcTest(DeviceController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "DEVICE_SERVICE_CLIENT_SECRET=test")
class AccessTokenTypeGateTest {

    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final HttpServer JWKS_SERVER = startJwksServer(SIGNING_KEY);

    private static final String CONTROL_BODY = "{\"field\":\"light\",\"state\":1}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private MqttClientService mqttClientService;

    @DynamicPropertySource
    static void jwkSetUri(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/oauth2/jwks");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    // -------------------------------------------------------------------------
    // Negative cases: at+jwt must be rejected before the controller runs
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"at+jwt", "application/at+jwt"})
    void getDevices_withAccessTokenType_returnsUnauthorized(String typ) throws Exception {
        mockMvc.perform(get("/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hubShapedToken(typ)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));

        verifyNoInteractions(deviceService, mqttClientService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"at+jwt", "application/at+jwt"})
    void controlDevice_withAccessTokenType_returnsUnauthorized(String typ) throws Exception {
        mockMvc.perform(post("/devices/1/control")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hubShapedToken(typ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTROL_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));

        verifyNoInteractions(deviceService, mqttClientService);
    }

    // -------------------------------------------------------------------------
    // Positive controls: same key, same claims, only typ differs
    // -------------------------------------------------------------------------

    @Test
    void getDevices_withJwtType_passesAuthentication() throws Exception {
        when(deviceService.getAllDevices()).thenReturn(List.of());

        mockMvc.perform(get("/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hubShapedToken("JWT")))
                .andExpect(status().isOk());

        verify(deviceService).getAllDevices();
    }

    @Test
    void controlDevice_withJwtType_passesAuthentication() throws Exception {
        when(deviceService.getDevice(1L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/devices/1/control")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hubShapedToken("JWT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTROL_BODY))
                .andExpect(status().isNotFound());

        verify(deviceService).getDevice(1L);
        verifyNoInteractions(mqttClientService);
    }

    @Test
    void getDevices_withoutTyp_passesAuthentication() throws Exception {
        // First-party tokens carry no typ header; they must keep working.
        when(deviceService.getAllDevices()).thenReturn(List.of());

        mockMvc.perform(get("/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hubShapedToken(null)))
                .andExpect(status().isOk());

        verify(deviceService).getAllDevices();
    }

    // -------------------------------------------------------------------------
    // Decoder identity and refusing layer
    // -------------------------------------------------------------------------

    @Test
    void productionDecoder_rejectsAccessTokenTypeInTypeValidator() throws Exception {
        assertThat(jwtDecoder).isInstanceOf(NimbusJwtDecoder.class);
        assertThat(Mockito.mockingDetails(jwtDecoder).isMock()).isFalse();

        String typed = hubShapedToken("at+jwt");
        assertThatThrownBy(() -> jwtDecoder.decode(typed))
                .isInstanceOfSatisfying(JwtValidationException.class, e ->
                        assertThat(e.getErrors()).anySatisfy(error -> {
                            assertThat(error.getErrorCode()).isEqualTo("invalid_token");
                            assertThat(error.getUri()).isEqualTo("https://datatracker.ietf.org/doc/html/rfc7515#section-4.1.9");
                            assertThat(error.getDescription()).startsWith("the given typ value needs to be one of");
                        }));

        assertThat(jwtDecoder.decode(hubShapedToken("JWT")).getClaimAsString("client_id"))
                .isEqualTo("claude-mcp-hub");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Claims as in spec 080 §4.2: hub audience, client_id, both hub scopes, no role. */
    private static String hubShapedToken(String typ) throws JOSEException {
        Instant now = Instant.now();
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(SIGNING_KEY.getKeyID());
        if (typ != null) {
            header.type(new JOSEObjectType(typ));
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://auth.furchert.ch")
                .subject("owner")
                .audience(List.of("https://mcp.furchert.ch/mcp"))
                .claim("client_id", "claude-mcp-hub")
                .claim("scope", List.of("mail:read", "calendar:read"))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(header.build(), claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("gate-test-key").generate();
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
