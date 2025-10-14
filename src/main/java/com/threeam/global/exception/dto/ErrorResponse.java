package com.threeam.global.exception.dto;

import com.threeam.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import org.springframework.validation.BindingResult;

@Getter
public class ErrorResponse {

    private final String code;
    private final String message;
    private final int status;
    private final LocalDateTime timestamp;
    private final List<ValidationError> errors;

    private ErrorResponse(ErrorCode errorCode, List<ValidationError> errors) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
        this.status = errorCode.getStatus().value();
        this.timestamp = LocalDateTime.now();
        this.errors = errors;
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode, Collections.emptyList());
    }

    public static ErrorResponse of(ErrorCode errorCode, BindingResult bindingResult) {
        return new ErrorResponse(errorCode, ValidationError.from(bindingResult));
    }

    @Getter
    public static class ValidationError {

        private final String field;
        private final String reason;

        private ValidationError(String field, String reason) {
            this.field = field;
            this.reason = reason;
        }

        private static List<ValidationError> from(BindingResult bindingResult) {
            return bindingResult.getFieldErrors().stream()
                    .map(error -> new ValidationError(error.getField(), error.getDefaultMessage()))
                    .toList();
        }
    }
}
