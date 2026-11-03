package ch.furchert.homelab.device.config;

import ch.furchert.homelab.device.controller.DeviceController;
import ch.furchert.homelab.device.service.DeviceRegistrationService;
import ch.furchert.homelab.device.service.DeviceService;
import ch.furchert.homelab.device.service.MqttClientService;
import com.nimbusds.jose.JOSEException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #81 (interim): issuer validation, ID-token rejection and the ADMIN rule for device writes, verified
 * with the production {@code JwtDecoder} against a JWKS served by the test (harness as in
 * {@code AccessTokenTypeGateTest}). Token shapes follow auth-service: user-driven grants carry an
 * unprefixed {@code role} claim, client_credentials tokens carry none, ID tokens carry {@code azp}.
 */
@WebMvcTest(DeviceController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "DEVICE_SERVICE_CLIENT_SECRET=test")
class DeviceAuthorizationTest {

    private static final String ISSUER = "https://auth.furchert.ch";
    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final HttpServer JWKS_SERVER = startJwksServer(SIGNING_KEY);

    private static final String CONTROL_BODY = "{\"field\":\"light\",\"state\":1}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private MqttClientService mqttClientService;

    @MockitoBean
    private DeviceRegistrationService deviceRegistrationService;

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
    // Control: ADMIN only
    // -------------------------------------------------------------------------

    @Test
    void control_withAdminRole_reachesController() throws Exception {
        when(deviceService.getDevice(1L)).thenReturn(Optional.empty());

        mockMvc.perform(control("/devices/1/control", userToken("ADMIN")))
                .andExpect(status().isNotFound());

        verify(deviceService).getDevice(1L);
        verifyNoInteractions(mqttClientService);
    }

    @Test
    void control_withUserRole_isForbidden() throws Exception {
        mockMvc.perform(control("/devices/1/control", userToken("USER")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")));

        verifyNoInteractions(deviceService, mqttClientService);
    }

    @Test
    void control_withClientCredentialsToken_isForbidden() throws Exception {
        // client_credentials tokens (furchert-ch server-side, device clients) carry no role claim.
        mockMvc.perform(control("/devices/1/control", clientCredentialsToken()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deviceService, mqttClientService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/devices/1/control/", "/devices/1/%63ontrol"})
    void control_pathVariantsWithUserRole_areForbidden(String path) throws Exception {
        // URI.create keeps the percent-encoding as sent; post(String) would encode it a second time.
        mockMvc.perform(post(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTROL_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deviceService, mqttClientService);
    }

    @Test
    void otherWriteMethods_withUserRole_areForbidden() throws Exception {
        String token = userToken("USER");
        for (MockHttpServletRequestBuilder request : List.of(
                put("/devices/1"), patch("/devices/1"), delete("/devices/1"), post("/devices"))) {
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(deviceService, mqttClientService);
    }

    // -------------------------------------------------------------------------
    // Reads: any valid access token (owner decision 2026-11-02)
    // -------------------------------------------------------------------------

    @Test
    void getDevices_withUserRole_isOk() throws Exception {
        when(deviceService.getAllDevices()).thenReturn(List.of());

        mockMvc.perform(get("/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken("USER")))
                .andExpect(status().isOk());
    }

    @Test
    void getDevices_withClientCredentialsToken_isOk() throws Exception {
        when(deviceService.getAllDevices()).thenReturn(List.of());

        mockMvc.perform(get("/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + clientCredentialsToken()))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Issuer and ID tokens: 401 before authorization
    // -------------------------------------------------------------------------

    @Test
    void wrongIssuer_isUnauthorized() throws Exception {
        assertUnauthorized(token(claims -> claims.issuer("https://evil.example").claim("role", "ADMIN")));
    }

    @Test
    void missingIssuer_isUnauthorized() throws Exception {
        assertUnauthorized(token(claims -> claims.issuer(null).claim("role", "ADMIN")));
    }

    @Test
    void idToken_isUnauthorized() throws Exception {
        // Shape of an auth-service ID token: azp + aud = the client id, role of the signed-in user.
        assertUnauthorized(token(claims -> claims
                .audience("grafana")
                .claim("azp", "grafana")
                .claim("role", "ADMIN")));
    }

    // -------------------------------------------------------------------------
    // Authority mapping
    // -------------------------------------------------------------------------

    @Test
    void converter_mapsRoleAndScopes() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("role", "ADMIN")
                .claim("scope", List.of("openid", "profile"))
                .build();

        List<String> authorities = SecurityConfig.jwtAuthenticationConverter().convert(jwt).getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).contains("ROLE_ADMIN", "SCOPE_openid", "SCOPE_profile");
    }

    @Test
    void converter_withoutRole_yieldsNoRoleAuthority() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("scope", List.of("netmon:read"))
                .build();

        List<String> authorities = SecurityConfig.jwtAuthenticationConverter().convert(jwt).getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).contains("SCOPE_netmon:read").noneMatch(a -> a.startsWith("ROLE_"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
        mockMvc.perform(control("/devices/1/control", token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));

        verifyNoInteractions(deviceService, mqttClientService);
    }

    private static MockHttpServletRequestBuilder control(String path, String token) {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CONTROL_BODY);
    }

    /** Access token of a signed-in user (authorization_code), default shape: aud = client id, role. */
    private static String userToken(String role) throws JOSEException {
        return token(claims -> claims.audience("furchert-ch").claim("role", role));
    }

    /** client_credentials access token: no role claim. */
    private static String clientCredentialsToken() throws JOSEException {
        return token(claims -> claims
                .subject("furchert-ch")
                .audience("furchert-ch")
                .claim("scope", List.of("netmon:read")));
    }

    private static String token(Consumer<JWTClaimsSet.Builder> customizer) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("owner")
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .jwtID(UUID.randomUUID().toString());
        customizer.accept(claims);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("authz-test-key").generate();
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
