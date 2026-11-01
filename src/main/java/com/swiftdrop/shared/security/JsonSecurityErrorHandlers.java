package com.swiftdrop.shared.security;

import com.swiftdrop.shared.exception.ErrorCode;
import com.swiftdrop.shared.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** Writes 401/403 from the security filter chain in the same JSON shape as GlobalExceptionHandler. */
@Component
public class JsonSecurityErrorHandlers {

    private final JsonMapper jsonMapper;

    public JsonSecurityErrorHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** No cookie, or the token is invalid or expired. */
    public AuthenticationEntryPoint unauthenticated() {
        return (request, response, ex) -> write(response, ErrorCode.UNAUTHENTICATED);
    }

    /** Logged in, but the role is not allowed. */
    public AccessDeniedHandler forbidden() {
        return (request, response, ex) -> write(response, ErrorCode.FORBIDDEN);
    }

    /** Writes the error for {@code code} in the API.md shape. */
    public void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
    }
}
