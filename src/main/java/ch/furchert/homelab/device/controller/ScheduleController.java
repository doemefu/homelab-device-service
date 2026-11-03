package ch.furchert.homelab.device.controller;

import ch.furchert.homelab.device.dto.ScheduleDto;
import ch.furchert.homelab.device.dto.ScheduleRequest;
import ch.furchert.homelab.device.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Schedules REST API (#41). Reads need any valid access token, writes need role ADMIN
 * (enforced in {@code SecurityConfig}). Writes take effect in the scheduler immediately.
 */
@RestController
@RequestMapping("/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /** All schedules ordered by id, optionally filtered by {@code device} (the label). Unpaginated. */
    @GetMapping
    public List<ScheduleDto> list(@RequestParam(name = "device", required = false) String device) {
        return scheduleService.list(device);
    }

    @GetMapping("/{id}")
    public ScheduleDto get(@PathVariable Long id) {
        return scheduleService.get(id);
    }

    @PostMapping
    public ResponseEntity<ScheduleDto> create(@Valid @RequestBody ScheduleRequest request) {
        ScheduleDto created = scheduleService.create(request);
        return ResponseEntity.created(URI.create("/schedules/" + created.id())).body(created);
    }

    /** Full replace; every field is required. */
    @PutMapping("/{id}")
    public ScheduleDto update(@PathVariable Long id, @Valid @RequestBody ScheduleRequest request) {
        return scheduleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scheduleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
