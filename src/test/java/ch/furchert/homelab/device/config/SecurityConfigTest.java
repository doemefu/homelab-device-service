package ch.furchert.homelab.device.config;

import ch.furchert.homelab.device.controller.DeviceController;
import ch.furchert.homelab.device.service.DeviceService;
import ch.furchert.homelab.device.service.MqttClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "DEVICE_SERVICE_CLIENT_SECRET=test")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private MqttClientService mqttClientService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void swaggerUi_unauthenticated_redirectsToOAuth2Login() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/device-service"));
    }

    @Test
    void swaggerUi_authenticated_isNotRedirectedToLogin() throws Exception {
        // Verify the security layer does NOT challenge an already-authenticated user.
        // In @WebMvcTest, Springdoc may return 5xx because its resource handlers lack
        // full context — that is an application error, not a security failure.
        // The only security failures are: 401 (rejected) or 302 to the OAuth2 login URL.
        var result = mockMvc.perform(get("/swagger-ui/index.html").with(oauth2Login())).andReturn();
        int status = result.getResponse().getStatus();
        String location = result.getResponse().getHeader("Location");
        assertThat(status).isNotEqualTo(401);
        assertThat(status == 302 && location != null && location.contains("oauth2/authorization"))
                .as("authenticated user must not be redirected to OAuth2 login")
                .isFalse();
    }

    @Test
    void apiDevices_withoutJwt_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/devices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiDevices_withJwt_isHandled() throws Exception {
        when(deviceService.getAllDevices()).thenReturn(List.of());

        mockMvc.perform(get("/devices").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void actuatorHealth_isPublicOrMissingButNotAuthProtected() throws Exception {
        int status = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        assertThat(status).as("actuator/health must not require authentication").isNotIn(302, 401);
    }

    @Test
    void actuatorInfo_isPublicOrMissingButNotAuthProtected() throws Exception {
        int status = mockMvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus();
        assertThat(status).as("actuator/info must not require authentication").isNotIn(302, 401);
    }

    @Test
    void webjars_unauthenticated_isPermitAll() throws Exception {
        // /webjars/** is permitAll() in Chain 1 — Swagger UI static assets must load
        // without triggering an OAuth2 redirect (the page would otherwise break).
        var result = mockMvc.perform(get("/webjars/swagger-ui/index.css")).andReturn();
        int status = result.getResponse().getStatus();
        String location = result.getResponse().getHeader("Location");
        assertThat(status).as("webjars path must not require JWT").isNotEqualTo(401);
        assertThat(status == 302 && location != null && location.contains("oauth2/authorization"))
                .as("webjars path must not redirect to OAuth2 login")
                .isFalse();
    }

    @Test
    void ws_unauthenticated_isPermitAll() throws Exception {
        // /ws/** is permitAll() in Chain 2 — WebSocket upgrades must not require a JWT
        // so that the browser client can connect without a Bearer token.
        int status = mockMvc.perform(get("/ws/websocket")).andReturn().getResponse().getStatus();
        assertThat(status).as("WebSocket path must not require authentication").isNotIn(401, 302);
    }
}
