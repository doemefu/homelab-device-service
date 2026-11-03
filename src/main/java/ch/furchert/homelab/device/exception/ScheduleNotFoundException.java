package ch.furchert.homelab.device.exception;

/**
 * Thrown when an operation targets a schedule id that does not exist.
 * Mapped to HTTP 404 by {@code GlobalExceptionHandler}.
 */
public class ScheduleNotFoundException extends RuntimeException {
    public ScheduleNotFoundException(Long id) {
        super("Schedule not found: " + id);
    }
}
