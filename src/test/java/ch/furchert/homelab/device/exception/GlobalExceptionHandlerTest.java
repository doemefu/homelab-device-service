package ch.furchert.homelab.device.exception;

import ch.furchert.homelab.device.config.SecurityConfig;
import ch.furchert.homelab.device.controller.DeviceController;
import ch.furchert.homelab.device.service.DeviceService;
import ch.furchert.homelab.device.service.MqttClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the {@link GlobalExceptionHandler} response payload contract.
 *
 * <p>Uses a real MVC stack ({@link WebMvcTest}) to verify that:
 * <ul>
 *   <li>Validation failures produce a 400 with {@code error} + {@code details} keys.</li>
 *   <li>Unhandled exceptions produce a 500 with only a {@code error} key and no
 *       internal message is leaked to the caller.</li>
 * </ul>
 */
@WebMvcTest(DeviceController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "DEVICE_SERVICE_CLIENT_SECRET=test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private MqttClientService mqttClientService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // -------------------------------------------------------------------------
    // 400 — validation failure (MethodArgumentNotValidException)
    // -------------------------------------------------------------------------

    @Test
    void validationFailure_returns400WithErrorKey() throws Exception {
        // "field" value violates @Pattern — triggers MethodArgumentNotValidException
        String body = "{\"field\":\"invalid-field\",\"state\":1}";

        mockMvc.perform(post("/devices/1/control")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    void validationFailure_returns400WithDetailsArray() throws Exception {
        String body = "{\"field\":\"invalid-field\",\"state\":1}";

        mockMvc.perform(post("/devices/1/control")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details.length()").value(greaterThan(0)));
    }

    @Test
    void validationFailure_detailsDescribeViolatedField() throws Exception {
        // Both field and state violate constraints simultaneously
        String body = "{\"field\":\"bad\",\"state\":99}";

        mockMvc.perform(post("/devices/1/control")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray())
                // Each detail string starts with the field name
                .andExpect(jsonPath("$.details", hasItem(org.hamcrest.Matchers.startsWith("field:"))));
    }

    // -------------------------------------------------------------------------
    // 500 — unhandled exception
    // -------------------------------------------------------------------------

    @Test
    void unhandledException_returns500WithGenericErrorKey() throws Exception {
        when(deviceService.getAllDevices()).thenThrow(new RuntimeException("DB down"));

        mockMvc.perform(get("/devices").with(jwt()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
    }

    @Test
    void unhandledException_doesNotLeakInternalExceptionMessage() throws Exception {
        when(deviceService.getAllDevices()).thenThrow(new RuntimeException("secret-internal-message"));

        mockMvc.perform(get("/devices").with(jwt()))
                .andExpect(status().isInternalServerError())
                // The raw exception message must never reach the response body
                .andExpect(jsonPath("$.error").value(not("secret-internal-message")))
                .andExpect(jsonPath("$").value(not(org.hamcrest.Matchers.hasKey("details"))));
    }
}
