package com.swiftdrop.merchants.internal.security;

import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.merchants.MerchantApi;
import com.swiftdrop.merchants.internal.application.ApiKeyService;
import com.swiftdrop.merchants.internal.domain.MerchantApiKey;
import com.swiftdrop.shared.error.ApiError;
import com.swiftdrop.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates requests that carry an {@code X-Api-Key} header.
 * Hashes the key, looks it up, checks the merchant is still approved, then sets a
 * {@code UsernamePasswordAuthenticationToken} with {@code ROLE_MERCHANT} so the rest of the
 * security chain treats the caller like a cookie-authenticated merchant.
 *
 * <p>Must be wired into {@code SecurityConfig} before {@code BearerTokenAuthenticationFilter}:
 * {@code .addFilterBefore(apiKeyAuthenticationFilter, BearerTokenAuthenticationFilter.class)}
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Api-Key";

    private final ApiKeyService apiKeyService;
    private final MerchantApi merchantApi;
    private final JsonMapper jsonMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, MerchantApi merchantApi, JsonMapper jsonMapper) {
        this.apiKeyService = apiKeyService;
        this.merchantApi = merchantApi;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawKey = request.getHeader(API_KEY_HEADER);
        if (rawKey == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<MerchantApiKey> maybeKey = apiKeyService.findActiveByHash(rawKey);
        if (maybeKey.isEmpty()) {
            writeError(response, ErrorCode.API_KEY_REVOKED);
            return;
        }

        MerchantApiKey key = maybeKey.get();
        boolean approved = merchantApi.findByUserId(key.getMerchantUserId())
                .map(summary -> summary.approvalStatus() == ApprovalStatus.APPROVED)
                .orElse(false);

        if (!approved) {
            writeError(response, ErrorCode.MERCHANT_NOT_APPROVED);
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                key.getMerchantUserId(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), ApiError.of(code));
    }
}
