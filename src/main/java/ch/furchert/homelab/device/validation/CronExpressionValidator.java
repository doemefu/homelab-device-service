package ch.furchert.homelab.device.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZonedDateTime;

/**
 * Validates with the same parser {@code CronTrigger} uses, and rejects expressions that never
 * fire (e.g. 30 February), which the scheduler would otherwise reject on every reload.
 */
public class CronExpressionValidator implements ConstraintValidator<ValidCron, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (!CronExpression.isValidExpression(value)) {
            return false;
        }
        return CronExpression.parse(value).next(ZonedDateTime.now()) != null;
    }
}
