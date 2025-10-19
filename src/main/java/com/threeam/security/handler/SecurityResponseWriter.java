package com.threeam.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

public final class SecurityResponseWriter {

    private SecurityResponseWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode)
            throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
    }
}
