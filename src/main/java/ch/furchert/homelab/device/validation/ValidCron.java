package ch.furchert.homelab.device.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Spring cron expression (6 fields, or a macro such as {@code @daily}) that the scheduler
 * accepts and that fires at least once in the future. {@code null} is left to {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = CronExpressionValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCron {

    String message() default "must be a valid Spring cron expression (6 fields) that fires in the future";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
