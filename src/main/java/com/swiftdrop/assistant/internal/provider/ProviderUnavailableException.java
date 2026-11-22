package com.swiftdrop.assistant.internal.provider;

/** The model couldn't answer (network, quota, timeout, empty reply). The message never contains the API key. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
