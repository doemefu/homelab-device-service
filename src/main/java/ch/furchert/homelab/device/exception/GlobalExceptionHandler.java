package ch.furchert.homelab.device.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Translates exceptions thrown by controllers into structured HTTP error responses.
 *
 * <p>No sensitive data (tokens, passwords) is ever included in the response body.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles bean-validation failures from {@code @Valid}-annotated request bodies.
     *
     * @param ex the validation exception
     * @return 400 with an error object listing all field-level violations
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();

        log.debug("Validation failed: {}", details);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "Validation failed",
                        "details", details
                ));
    }

    /**
     * Handles a duplicate device name (local or auth-service 409 conflict).
     *
     * @param ex the conflict exception
     * @return 409 with a generic error message (no request/response echo —
     *         the registration response carries a one-time client secret)
     */
    @ExceptionHandler(DeviceAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleConflict(DeviceAlreadyExistsException ex) {
        log.info("Device registration conflict: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Handles an operation targeting an unknown device.
     *
     * @param ex the not-found exception
     * @return 404 with a generic error message
     */
    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(DeviceNotFoundException ex) {
        log.info("Device not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Catch-all handler for any unhandled exception.
     *
     * <p>The exception message is intentionally not forwarded to the client
     * to avoid leaking internal details.
     *
     * @param ex the exception
     * @return 500 with a generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }
}
