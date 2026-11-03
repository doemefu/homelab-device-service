package ch.furchert.homelab.device.service;

import ch.furchert.homelab.device.dto.ScheduleDto;
import ch.furchert.homelab.device.dto.ScheduleRequest;
import ch.furchert.homelab.device.entity.Schedule;
import ch.furchert.homelab.device.exception.ScheduleNotFoundException;
import ch.furchert.homelab.device.exception.UnknownDeviceException;
import ch.furchert.homelab.device.repository.DeviceRepository;
import ch.furchert.homelab.device.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * CRUD for the {@code schedules} table behind the schedules REST API (#41, ADR 0001).
 *
 * <p>Each write is committed by its own repository call before
 * {@link SchedulerService#reloadSchedules()} runs, so the reload sees the new row and the
 * scheduler lock is never held inside an open transaction. A failed reload does not fail the
 * request: the row is committed and the periodic reload picks it up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final DeviceRepository deviceRepository;
    private final SchedulerService schedulerService;
    private final ObjectMapper objectMapper;

    /** All schedules ordered by id, optionally only those labelled with {@code deviceName}. */
    public List<ScheduleDto> list(String deviceName) {
        List<Schedule> schedules = deviceName == null
                ? scheduleRepository.findAllByOrderByIdAsc()
                : scheduleRepository.findByDeviceNameOrderByIdAsc(deviceName);
        return schedules.stream().map(this::toDto).toList();
    }

    public ScheduleDto get(Long id) {
        return toDto(scheduleRepository.findById(id).orElseThrow(() -> new ScheduleNotFoundException(id)));
    }

    public ScheduleDto create(ScheduleRequest request) {
        requireDevice(request.deviceName());
        Schedule saved = scheduleRepository.save(apply(new Schedule(), request));
        log.info("Created schedule id={} field={} cron='{}' active={}",
                saved.getId(), saved.getField(), saved.getCronExpression(), saved.getActive());
        reload();
        return toDto(saved);
    }

    /** Full replace of an existing schedule. */
    public ScheduleDto update(Long id, ScheduleRequest request) {
        Schedule schedule = scheduleRepository.findById(id).orElseThrow(() -> new ScheduleNotFoundException(id));
        requireDevice(request.deviceName());
        Schedule saved = scheduleRepository.save(apply(schedule, request));
        log.info("Updated schedule id={} field={} cron='{}' active={}",
                saved.getId(), saved.getField(), saved.getCronExpression(), saved.getActive());
        reload();
        return toDto(saved);
    }

    public void delete(Long id) {
        if (!scheduleRepository.existsById(id)) {
            throw new ScheduleNotFoundException(id);
        }
        scheduleRepository.deleteById(id);
        log.info("Deleted schedule id={}", id);
        reload();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void requireDevice(String deviceName) {
        if (!deviceRepository.existsByName(deviceName)) {
            throw new UnknownDeviceException(deviceName);
        }
    }

    private Schedule apply(Schedule schedule, ScheduleRequest request) {
        schedule.setDeviceName(request.deviceName());
        schedule.setField(request.field());
        schedule.setPayload(payload(request.field(), request.state()));
        schedule.setCronExpression(request.cronExpression());
        schedule.setActive(request.active());
        return schedule;
    }

    private void reload() {
        try {
            schedulerService.reloadSchedules();
        } catch (RuntimeException e) {
            log.warn("Schedule change saved, but the immediate reload failed; the periodic reload "
                    + "will apply it: {}", e.getMessage(), e);
        }
    }

    /** Same shape as manual control: {@code {"<Field>State": <state>}}. */
    private String payload(String field, int state) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(stateKey(field), state);
        return node.toString();
    }

    private ScheduleDto toDto(Schedule s) {
        boolean active = Boolean.TRUE.equals(s.getActive());
        return new ScheduleDto(s.getId(), s.getDeviceName(), s.getField(), state(s), s.getPayload(),
                s.getCronExpression(), active, active ? nextRun(s.getCronExpression()) : null);
    }

    /** The state of a standard payload, or {@code null} for anything else. */
    private Integer state(Schedule s) {
        if (s.getField() == null || s.getField().isEmpty() || s.getPayload() == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(s.getPayload());
            JsonNode value = node.get(stateKey(s.getField()));
            if (node.isObject() && node.size() == 1 && value != null && value.isInt()
                    && (value.intValue() == 0 || value.intValue() == 1)) {
                return value.intValue();
            }
        } catch (JacksonException e) {
            log.debug("Schedule id={} has a non-JSON payload", s.getId());
        }
        return null;
    }

    private static OffsetDateTime nextRun(String cron) {
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            return null;
        }
        ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now());
        return next == null ? null : next.toOffsetDateTime();
    }

    private static String stateKey(String field) {
        return Character.toUpperCase(field.charAt(0)) + field.substring(1) + "State";
    }
}
