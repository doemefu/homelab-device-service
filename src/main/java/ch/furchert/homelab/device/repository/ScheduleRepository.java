package ch.furchert.homelab.device.repository;

import ch.furchert.homelab.device.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link Schedule} entities.
 * <p>
 * Provides read access to the {@code schedules} table, which is owned by
 * homelab-data-service. Only query methods are used; writes go through the
 * data-service REST API.
 */
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    /**
     * Returns all schedules that are currently active.
     * <p>
     * Called on startup and after schedule-change events to rebuild the
     * in-process {@code ThreadPoolTaskScheduler} task map.
     *
     * @return list of active schedules (never null, may be empty)
     */
    List<Schedule> findByActiveTrue();
}
