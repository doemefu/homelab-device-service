package ch.furchert.homelab.device.repository;

import ch.furchert.homelab.device.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link Schedule} entities.
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
