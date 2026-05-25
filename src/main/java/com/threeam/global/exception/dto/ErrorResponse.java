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
    // 쿨다운 거절에만 채워진다(그 외 null). 화면이 남은 시간을 카운트다운으로 보여주는 근거 —
    // 메시지 문자열에 적어 프론트가 파싱하게 두지 않는다.
    private final Integer retryAfterSeconds;

    private ErrorResponse(ErrorCode errorCode, List<ValidationError> errors,
                          Integer retryAfterSeconds) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
        this.status = errorCode.getStatus().value();
        this.timestamp = LocalDateTime.now();
        this.errors = errors;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode, Collections.emptyList(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, int retryAfterSeconds) {
        return new ErrorResponse(errorCode, Collections.emptyList(), retryAfterSeconds);
    }

    public static ErrorResponse of(ErrorCode errorCode, BindingResult bindingResult) {
        return new ErrorResponse(errorCode, ValidationError.from(bindingResult), null);
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
