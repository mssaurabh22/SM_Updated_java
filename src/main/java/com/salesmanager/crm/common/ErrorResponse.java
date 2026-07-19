package com.salesmanager.crm.common;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Consistent error response shape returned by {@link GlobalExceptionHandler}.
 */
@Getter
@Builder
@AllArgsConstructor
public class ErrorResponse {

    private final OffsetDateTime timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String path;
    private final List<FieldErrorDetail> fieldErrors;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class FieldErrorDetail {
        private final String field;
        private final String message;
    }
}
