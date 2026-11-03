package com.swiftdrop.identity.internal.validation;

/** Asks DNS whether a domain can receive email. Replaced by a fake in tests (no real DNS). */
public interface MxLookup {

    enum MxStatus {
        /** The domain has at least one real MX record. */
        FOUND,
        /** The domain does not exist, has no MX records, or says it takes no mail (null MX "0 ."). */
        NOT_FOUND,
        /** DNS could not answer (timeout, no network, server error): the address is given the benefit of the doubt. */
        LOOKUP_FAILED
    }

    /** {@code domain} is the part after the @, lower-case. Must answer within about 3 seconds. */
    MxStatus lookup(String domain);
}
