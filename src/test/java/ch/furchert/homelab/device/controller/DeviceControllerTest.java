package ch.furchert.homelab.device.controller;

import ch.furchert.homelab.device.entity.Device;
import ch.furchert.homelab.device.service.DeviceService;
import ch.furchert.homelab.device.service.MqttClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice tests for {@link DeviceController}.
 *
 * <p>Uses {@link WebMvcTest} to load only the web layer. Security is active;
 * requests carry a mock JWT via {@code SecurityMockMvcRequestPostProcessors.jwt()}.
 */
@WebMvcTest(DeviceController.class)
class DeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private MqttClientService mqttClientService;

    // -------------------------------------------------------------------------
    // GET /devices
    // -------------------------------------------------------------------------

    @Test
    void getAllDevices_returnsOkWithList() throws Exception {
        Device d1 = Device.builder().id(1L).name("terra1").mqttOnline(true).build();
        Device d2 = Device.builder().id(2L).name("terra2").mqttOnline(false).build();
        when(deviceService.getAllDevices()).thenReturn(List.of(d1, d2));

        mockMvc.perform(get("/devices")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("terra1"))
                .andExpect(jsonPath("$[1].name").value("terra2"));
    }

    @Test
    void getAllDevices_withoutJwt_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/devices"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /devices/{id}
    // -------------------------------------------------------------------------

    @Test
    void getDevice_found_returnsOk() throws Exception {
        Device d = Device.builder().id(1L).name("terra1").mqttOnline(true)
                .temperature(22.5).humidity(65.0).build();
        when(deviceService.getDevice(1L)).thenReturn(Optional.of(d));

        mockMvc.perform(get("/devices/1")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("terra1"))
                .andExpect(jsonPath("$.temperature").value(22.5));
    }

    @Test
    void getDevice_notFound_returns404() throws Exception {
        when(deviceService.getDevice(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/devices/99")
                        .with(jwt()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /devices/{id}/control
    // -------------------------------------------------------------------------

    @Test
    void controlDevice_validBody_returnsOkAndPublishesMqtt() throws Exception {
        Device d = Device.builder().id(1L).name("terra1").build();
        when(deviceService.getDevice(1L)).thenReturn(Optional.of(d));

        String body = "{\"field\":\"light\",\"state\":1}";

        mockMvc.perform(post("/devices/1/control")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Verify MQTT publish was called with the correct topic
        verify(mqttClientService).publish(
                eq("terra1/light/man"),
                eq("{\"LightState\":1}"),
                eq(1),
                eq(false)
        );
    }

    @Test
    void controlDevice_deviceNotFound_returns404() throws Exception {
        when(deviceService.getDevice(99L)).thenReturn(Optional.empty());

        String body = "{\"field\":\"light\",\"state\":1}";

        mockMvc.perform(post("/devices/99/control")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        verifyNoInteractions(mqttClientService);
    }

    @Test
    void controlDevice_missingField_returns400() throws Exception {
        // "field" is @NotBlank — omitting it should trigger validation failure
        String body = "{\"state\":1}";

        mockMvc.perform(post("/devices/1/control")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void controlDevice_missingState_returns400() throws Exception {
        // "state" is @NotNull — omitting it should trigger validation failure
        String body = "{\"field\":\"light\"}";

        mockMvc.perform(post("/devices/1/control")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
