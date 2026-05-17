package ch.furchert.homelab.device.controller;

import ch.furchert.homelab.device.config.SecurityConfig;
import ch.furchert.homelab.device.dto.DeviceRegisteredResponse;
import ch.furchert.homelab.device.dto.RegisterDeviceRequest;
import ch.furchert.homelab.device.exception.DeviceAlreadyExistsException;
import ch.furchert.homelab.device.exception.DeviceNotFoundException;
import ch.furchert.homelab.device.service.DeviceRegistrationService;
import ch.furchert.homelab.device.service.DeviceService;
import ch.furchert.homelab.device.service.MqttClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice tests for the registration endpoints of {@link DeviceController}.
 * ADMIN role is supplied via {@code jwt().authorities(ROLE_ADMIN)}; the
 * {@code role}-claim → {@code ROLE_*} mapping itself is covered by
 * {@code JwtAuthenticationConverterTest}.
 */
@WebMvcTest(DeviceController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "DEVICE_SERVICE_CLIENT_SECRET=test")
class DeviceControllerRegistrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private DeviceRegistrationService deviceRegistrationService;

    @MockitoBean
    private MqttClientService mqttClientService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("ROLE_ADMIN");
    private static final String VALID_BODY = "{\"name\":\"terra3\",\"type\":\"terrarium\",\"description\":\"d\"}";

    @Test
    void register_asAdmin_returns201WithSecret() throws Exception {
        when(deviceRegistrationService.register(any(RegisterDeviceRequest.class)))
                .thenReturn(new DeviceRegisteredResponse("terra3", "terrarium", "terra3",
                        "plaintext-secret", List.of("mqtt:pub", "mqtt:sub"),
                        Instant.parse("2026-05-16T10:00:00Z"), "terra3",
                        List.of("terra3/#", "terraGeneral/#")));

        mockMvc.perform(post("/devices")
                        .with(jwt().authorities(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("terra3"))
                .andExpect(jsonPath("$.clientSecret").value("plaintext-secret"))
                .andExpect(jsonPath("$.mqttTopicsAllowed[0]").value("terra3/#"));
    }

    @Test
    void register_nonAdminJwt_returns403() throws Exception {
        mockMvc.perform(post("/devices")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deviceRegistrationService);
    }

    @Test
    void register_noJwt_isRejected() throws Exception {
        // Unauthenticated state-changing request: the CsrfFilter rejects it
        // (403) before the bearer-token entry point runs. Either way the
        // request never reaches the controller — that is the security contract.
        mockMvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deviceRegistrationService);
    }

    @Test
    void register_duplicate_returns409() throws Exception {
        when(deviceRegistrationService.register(any()))
                .thenThrow(new DeviceAlreadyExistsException("terra3"));

        mockMvc.perform(post("/devices")
                        .with(jwt().authorities(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void register_invalidName_returns400() throws Exception {
        mockMvc.perform(post("/devices")
                        .with(jwt().authorities(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AB\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deviceRegistrationService);
    }

    @Test
    void delete_asAdmin_returns204() throws Exception {
        mockMvc.perform(delete("/devices/terra3")
                        .with(jwt().authorities(ADMIN)))
                .andExpect(status().isNoContent());

        verify(deviceRegistrationService).delete("terra3");
    }

    @Test
    void delete_unknown_returns404() throws Exception {
        doThrow(new DeviceNotFoundException("nope"))
                .when(deviceRegistrationService).delete("nope");

        mockMvc.perform(delete("/devices/nope")
                        .with(jwt().authorities(ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_nonAdminJwt_returns403() throws Exception {
        mockMvc.perform(delete("/devices/terra3").with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deviceRegistrationService);
    }
}
