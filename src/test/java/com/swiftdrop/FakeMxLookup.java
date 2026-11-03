package com.swiftdrop;

import com.swiftdrop.identity.internal.validation.MxLookup;

/**
 * Replaces the real DNS lookup in tests (no network):
 * <ul>
 *   <li>domains ending in {@code no-mx.test} have no MX records,</li>
 *   <li>domains ending in {@code dns-down.test} make the lookup fail (DNS error → allowed),</li>
 *   <li>everything else has MX records.</li>
 * </ul>
 */
public class FakeMxLookup implements MxLookup {

    public static final String NO_MX_DOMAIN = "no-mx.test";
    public static final String DNS_DOWN_DOMAIN = "dns-down.test";

    @Override
    public MxStatus lookup(String domain) {
        if (domain.endsWith(NO_MX_DOMAIN)) {
            return MxStatus.NOT_FOUND;
        }
        if (domain.endsWith(DNS_DOWN_DOMAIN)) {
            return MxStatus.LOOKUP_FAILED;
        }
        return MxStatus.FOUND;
    }
}
