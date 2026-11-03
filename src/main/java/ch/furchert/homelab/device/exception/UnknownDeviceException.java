package ch.furchert.homelab.device.exception;

/**
 * Thrown when a schedule names a device that does not exist.
 * Mapped to HTTP 400 (validation shape) by {@code GlobalExceptionHandler}.
 */
public class UnknownDeviceException extends RuntimeException {
    public UnknownDeviceException(String name) {
        super("unknown device: " + name);
    }
}
