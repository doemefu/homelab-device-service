package ch.furchert.homelab.device.controller;

import ch.furchert.homelab.device.config.SecurityConfig;
import ch.furchert.homelab.device.dto.ScheduleDto;
import ch.furchert.homelab.device.dto.ScheduleRequest;
import ch.furchert.homelab.device.exception.ScheduleNotFoundException;
import ch.furchert.homelab.device.exception.UnknownDeviceException;
import ch.furchert.homelab.device.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice tests for {@link ScheduleController} with the production {@link SecurityConfig}:
 * reads and writes need ROLE_ADMIN (#41, #124), validation and error mapping.
 */
@WebMvcTest(ScheduleController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "DEVICE_SERVICE_CLIENT_SECRET=test")
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScheduleService scheduleService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String VALID = """
            {"deviceName":"terra1","field":"light","state":1,"cronExpression":"0 0 8 * * *","active":true}""";

    private static final ScheduleDto DTO = new ScheduleDto(
            5L, "terra1", "light", 1, "{\"LightState\":1}", "0 0 8 * * *", true, null);

    private static JwtRequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static JwtRequestPostProcessor user() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    // --- reads -------------------------------------------------------------

    @Test
    void list_admin_returnsSchedules() throws Exception {
        when(scheduleService.list(null)).thenReturn(List.of(DTO));

        mockMvc.perform(get("/schedules").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].state").value(1))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void list_withDeviceFilter_passesItOn() throws Exception {
        when(scheduleService.list("terra2")).thenReturn(List.of());

        mockMvc.perform(get("/schedules").param("device", "terra2").with(admin()))
                .andExpect(status().isOk());

        verify(scheduleService).list("terra2");
    }

    @Test
    void reads_withUserRole_return403() throws Exception {
        mockMvc.perform(get("/schedules").with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get("/schedules/5").with(user())).andExpect(status().isForbidden());
        verifyNoInteractions(scheduleService);
    }

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/schedules")).andExpect(status().isUnauthorized());
        verifyNoInteractions(scheduleService);
    }

    @Test
    void get_knownId_returnsSchedule() throws Exception {
        when(scheduleService.get(5L)).thenReturn(DTO);

        mockMvc.perform(get("/schedules/5").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cronExpression").value("0 0 8 * * *"));
    }

    @Test
    void get_unknownId_returns404() throws Exception {
        when(scheduleService.get(9L)).thenThrow(new ScheduleNotFoundException(9L));

        mockMvc.perform(get("/schedules/9").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Schedule not found: 9"));
    }

    @Test
    void get_nonNumericId_returns400() throws Exception {
        mockMvc.perform(get("/schedules/abc").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(scheduleService);
    }

    // --- writes ------------------------------------------------------------

    @Test
    void create_asAdmin_returns201WithLocation() throws Exception {
        when(scheduleService.create(any(ScheduleRequest.class))).thenReturn(DTO);

        mockMvc.perform(post("/schedules").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/schedules/5"))
                .andExpect(jsonPath("$.payload").value("{\"LightState\":1}"));
    }

    @Test
    void create_asUser_returns403() throws Exception {
        mockMvc.perform(post("/schedules").with(user())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scheduleService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"deviceName\":\"terra1\",\"field\":\"light\",\"state\":1,\"cronExpression\":\"not a cron\",\"active\":true}",
            "{\"deviceName\":\"terra1\",\"field\":\"light\",\"state\":1,\"cronExpression\":\"0 0 0 30 2 *\",\"active\":true}",
            "{\"deviceName\":\"terra1\",\"field\":\"door\",\"state\":1,\"cronExpression\":\"0 0 8 * * *\",\"active\":true}",
            "{\"deviceName\":\"terra1\",\"field\":\"light\",\"state\":2,\"cronExpression\":\"0 0 8 * * *\",\"active\":true}",
            "{\"deviceName\":\"terra1\",\"field\":\"light\",\"state\":1,\"cronExpression\":\"0 0 8 * * *\"}",
            "{\"deviceName\":\"Terra 1\",\"field\":\"light\",\"state\":1,\"cronExpression\":\"0 0 8 * * *\",\"active\":true}"
    })
    void create_invalidBody_returns400(String body) throws Exception {
        mockMvc.perform(post("/schedules").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
        verifyNoInteractions(scheduleService);
    }

    @Test
    void create_invalidCron_namesTheField() throws Exception {
        mockMvc.perform(post("/schedules").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("0 0 8 * * *", "61 * * * * *")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(startsWith("cronExpression: "))));
    }

    @Test
    void create_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/schedules").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceName\":"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(scheduleService);
    }

    @Test
    void create_unknownDevice_returns400InValidationShape() throws Exception {
        when(scheduleService.create(any(ScheduleRequest.class))).thenThrow(new UnknownDeviceException("terra1"));

        mockMvc.perform(post("/schedules").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]").value("deviceName: unknown device: terra1"));
    }

    @Test
    void update_asAdmin_returns200() throws Exception {
        when(scheduleService.update(eq(5L), any(ScheduleRequest.class))).thenReturn(DTO);

        mockMvc.perform(put("/schedules/5").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void update_unknownId_returns404() throws Exception {
        when(scheduleService.update(eq(9L), any(ScheduleRequest.class))).thenThrow(new ScheduleNotFoundException(9L));

        mockMvc.perform(put("/schedules/9").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asAdmin_returns204() throws Exception {
        mockMvc.perform(delete("/schedules/5").with(admin()))
                .andExpect(status().isNoContent());
        verify(scheduleService).delete(5L);
    }

    @Test
    void delete_unknownId_returns404() throws Exception {
        doThrow(new ScheduleNotFoundException(9L)).when(scheduleService).delete(9L);

        mockMvc.perform(delete("/schedules/9").with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asUser_returns403() throws Exception {
        mockMvc.perform(delete("/schedules/5").with(user()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scheduleService);
    }
}
