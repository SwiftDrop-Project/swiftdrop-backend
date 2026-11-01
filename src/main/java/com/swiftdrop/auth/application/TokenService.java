package com.swiftdrop.auth.application;

import com.swiftdrop.shared.config.SecurityProperties;
import com.swiftdrop.users.domain.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Issues HS256 JWTs. Claims: sub (user id), role, iat, exp. No personal data. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    public TokenService(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public String issue(UUID userId, Role role, boolean rememberMe) {
        Instant now = Instant.now();
        Duration lifetime = rememberMe
                ? Duration.ofDays(properties.jwt().rememberMeDays())
                : Duration.ofMinutes(properties.jwt().expiryMinutes());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("role", role.name())
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
