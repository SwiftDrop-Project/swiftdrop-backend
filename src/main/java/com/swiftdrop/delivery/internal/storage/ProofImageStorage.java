package com.swiftdrop.delivery.internal.storage;

/**
 * Where proof-of-delivery images are kept. Only the delivery module uses it; other modules go through
 * {@code DeliveryApi.storeProofPhoto} / {@code findProofPhoto}. Implementations keep images private: nothing here
 * returns a URL, so no image can be reached without going through the backend.
 *
 * <p>Keys are chosen by the caller from a random photo id, so they never hold personal data. Failures are reported as
 * {@link ProofStorageException}, which the caller turns into 503 SERVICE_UNAVAILABLE.
 */
public interface ProofImageStorage {

    /** Stored in delivery.proof_photos.storage, e.g. CLOUDINARY or MEMORY. */
    String name();

    /**
     * Stores the image under {@code key}. Never overwrites an existing image.
     *
     * @return the durable key to keep in the database (the same key, or the storage's own id for it)
     */
    String put(String key, byte[] content, String contentType);

    /** The image's bytes. */
    byte[] get(String key, String contentType);

    /** Removes the image (orphan clean-up or a failed save). Missing images are ignored. */
    void delete(String key, String contentType);
}
