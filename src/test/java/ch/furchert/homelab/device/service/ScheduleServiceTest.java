package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.dto.ScheduleDto;
import ch.furchert.homelab.device.dto.ScheduleRequest;
import ch.furchert.homelab.device.entity.Schedule;
import ch.furchert.homelab.device.exception.ScheduleNotFoundException;
import ch.furchert.homelab.device.exception.UnknownDeviceException;
import ch.furchert.homelab.device.repository.DeviceRepository;
import ch.furchert.homelab.device.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ScheduleService}: payload building, device check, reload after every
 * committed write (#41) and the DTO mapping.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private SchedulerService schedulerService;

    private ScheduleService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleService(scheduleRepository, deviceRepository, schedulerService, new ObjectMapper());
    }

    private static ScheduleRequest request(String field, int state, String cron, boolean active) {
        return new ScheduleRequest("terra1", field, state, cron, active);
    }

    private static Schedule withId(Schedule s, long id) {
        s.setId(id);
        return s;
    }

    @Test
    void create_buildsPayloadSavesThenReloads() {
        when(deviceRepository.existsByName("terra1")).thenReturn(true);
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(i -> withId(i.getArgument(0), 7L));

        ScheduleDto dto = service.create(request("nightLight", 1, "0 0 8 * * *", true));

        ArgumentCaptor<Schedule> saved = ArgumentCaptor.forClass(Schedule.class);
        InOrder inOrder = inOrder(scheduleRepository, schedulerService);
        inOrder.verify(scheduleRepository).save(saved.capture());
        inOrder.verify(schedulerService).reloadSchedules();
        assertThat(saved.getValue().getPayload()).isEqualTo("{\"NightLightState\":1}");
        assertThat(saved.getValue().getDeviceName()).isEqualTo("terra1");
        assertThat(saved.getValue().getActive()).isTrue();
        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.state()).isEqualTo(1);
        assertThat(dto.nextRun()).isNotNull();
    }

    @Test
    void create_unknownDevice_isRejectedWithoutWriteOrReload() {
        when(deviceRepository.existsByName("terra1")).thenReturn(false);

        assertThatThrownBy(() -> service.create(request("light", 1, "0 0 8 * * *", true)))
                .isInstanceOf(UnknownDeviceException.class);

        verify(scheduleRepository, never()).save(any());
        verifyNoInteractions(schedulerService);
    }

    @Test
    void create_reloadFails_stillReturnsTheSavedSchedule() {
        // The row is committed; the next periodic reload registers it.
        when(deviceRepository.existsByName("terra1")).thenReturn(true);
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(i -> withId(i.getArgument(0), 7L));
        doThrow(new RuntimeException("DB hiccup")).when(schedulerService).reloadSchedules();

        assertThat(service.create(request("light", 0, "0 0 8 * * *", true)).id()).isEqualTo(7L);
    }

    @Test
    void update_replacesAllFieldsAndReloads() {
        Schedule existing = Schedule.builder().id(3L).deviceName("terra1").field("light")
                .payload("{\"LightState\":1}").cronExpression("0 0 8 * * *").active(true).build();
        when(scheduleRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(deviceRepository.existsByName("terra1")).thenReturn(true);
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(i -> i.getArgument(0));

        ScheduleDto dto = service.update(3L, request("rain", 0, "0 30 20 * * *", false));

        assertThat(existing.getField()).isEqualTo("rain");
        assertThat(existing.getPayload()).isEqualTo("{\"RainState\":0}");
        assertThat(existing.getCronExpression()).isEqualTo("0 30 20 * * *");
        assertThat(existing.getActive()).isFalse();
        assertThat(dto.nextRun()).as("inactive schedules have no next run").isNull();
        verify(schedulerService).reloadSchedules();
    }

    @Test
    void update_unknownId_throws404WithoutWriteOrReload() {
        when(scheduleRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(9L, request("light", 1, "0 0 8 * * *", true)))
                .isInstanceOf(ScheduleNotFoundException.class);

        verify(scheduleRepository, never()).save(any());
        verifyNoInteractions(schedulerService);
    }

    @Test
    void delete_removesThenReloads() {
        when(scheduleRepository.existsById(3L)).thenReturn(true);

        service.delete(3L);

        InOrder inOrder = inOrder(scheduleRepository, schedulerService);
        inOrder.verify(scheduleRepository).deleteById(3L);
        inOrder.verify(schedulerService).reloadSchedules();
    }

    @Test
    void delete_unknownId_throws404() {
        when(scheduleRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ScheduleNotFoundException.class);

        verify(scheduleRepository, never()).deleteById(any());
        verifyNoInteractions(schedulerService);
    }

    @Test
    void list_filtersByDeviceWhenGiven() {
        when(scheduleRepository.findByDeviceNameOrderByIdAsc("terra2")).thenReturn(List.of());

        assertThat(service.list("terra2")).isEmpty();

        verify(scheduleRepository, never()).findAllByOrderByIdAsc();
    }

    @Test
    void toDto_nonStandardPayloadAndNullActive_mapSafely() {
        Schedule legacy = Schedule.builder().id(1L).deviceName("terra1").field("light")
                .payload("{\"LightState\": 1, \"extra\": true}").cronExpression("0 0 8 * * *").active(null).build();
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(legacy));

        ScheduleDto dto = service.get(1L);

        assertThat(dto.state()).isNull();
        assertThat(dto.payload()).isEqualTo("{\"LightState\": 1, \"extra\": true}");
        assertThat(dto.active()).isFalse();
        assertThat(dto.nextRun()).isNull();
    }

    @Test
    void toDto_standardPayloadWithSpaces_yieldsState() {
        Schedule seeded = Schedule.builder().id(1L).deviceName("terra1").field("light")
                .payload("{\"LightState\": 0}").cronExpression("0 0 8 * * *").active(true).build();
        when(scheduleRepository.findById(1L)).thenReturn(Optional.of(seeded));

        assertThat(service.get(1L).state()).isEqualTo(0);
    }
}
