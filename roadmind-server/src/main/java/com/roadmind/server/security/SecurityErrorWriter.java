package com.roadmind.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void unauthenticated(HttpServletResponse response) throws IOException {
        write(response, 401, "UNAUTHENTICATED", "请先建立有效会话");
    }

    public void forbidden(HttpServletResponse response, Exception exception) throws IOException {
        boolean csrfError = exception instanceof MissingCsrfTokenException
                || exception instanceof InvalidCsrfTokenException;
        write(
                response,
                403,
                csrfError ? "CSRF_TOKEN_INVALID" : "FORBIDDEN",
                csrfError ? "修改请求缺少有效的 CSRF Token" : "当前身份无权执行该操作");
    }

    private void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(code, message, Map.of()));
    }
}
