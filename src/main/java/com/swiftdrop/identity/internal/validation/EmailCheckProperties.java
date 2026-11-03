package com.swiftdrop.identity.internal.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from EMAIL_MX_CHECK. {@code mxEnabled=false} turns the DNS check off (e.g. offline development);
 * disposable domains are always rejected.
 */
@ConfigurationProperties("swiftdrop.email-checks")
public record EmailCheckProperties(@DefaultValue("true") boolean mxEnabled) {
}
