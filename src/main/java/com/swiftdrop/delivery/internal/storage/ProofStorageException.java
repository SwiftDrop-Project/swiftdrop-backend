package com.swiftdrop.delivery.internal.storage;

/**
 * The image storage couldn't store, read or delete an image (network, credentials, the provider is down). The message
 * never holds credentials, keys with personal data or image content.
 */
public class ProofStorageException extends RuntimeException {

    public ProofStorageException(String message) {
        super(message);
    }

    public ProofStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
