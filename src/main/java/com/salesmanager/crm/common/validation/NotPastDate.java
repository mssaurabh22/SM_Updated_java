package com.salesmanager.crm.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a {@link java.time.LocalDate} field is either {@code null} (optional fields
 * stay optional - use alongside {@code @NotNull} to also require presence) or today-or-future.
 * Applied to Visit's {@code visitDate}/{@code nextVisitDate} fields, matching the spec's
 * literal "VisitDate >= Today" rule, and equally to Lead's {@code nextFollowupDate}/
 * {@code expectedCloseDate} fields - same today-or-future semantics for any "the next thing
 * to do" date field, not a Visit-specific assumption. Each usage overrides {@code message()}
 * with field-specific wording.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotPastDateValidator.class)
public @interface NotPastDate {

    String message() default "must be today or a future date";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
