package com.swiftdrop.delivery.internal.storage;

/**
 * No image storage is configured (PROOF_STORAGE_PROVIDER unset): every call fails, so uploads answer 503 and no parcel
 * can be marked DELIVERED until real storage is set up. Nothing is ever kept in memory by accident.
 */
public class DisabledProofImageStorage implements ProofImageStorage {

    public static final String NAME = "DISABLED";

    private static final String MESSAGE = "Proof photo storage is not configured (PROOF_STORAGE_PROVIDER)";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        throw new ProofStorageException(MESSAGE);
    }

    @Override
    public byte[] get(String key, String contentType) {
        throw new ProofStorageException(MESSAGE);
    }

    @Override
    public void delete(String key, String contentType) {
        throw new ProofStorageException(MESSAGE);
    }
}
