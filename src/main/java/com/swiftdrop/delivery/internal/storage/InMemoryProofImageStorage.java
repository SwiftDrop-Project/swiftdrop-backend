package com.swiftdrop.delivery.internal.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps proof images in this backend's memory: for automated tests and local runs without Cloudinary. Images are lost
 * when the backend stops (the database rows stay and then point to nothing), so it must never be used in production;
 * the configuration logs a warning when it is on.
 */
public class InMemoryProofImageStorage implements ProofImageStorage {

    public static final String NAME = "MEMORY";

    private final Map<String, byte[]> images = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        if (images.putIfAbsent(key, content.clone()) != null) {
            throw new ProofStorageException("An image is already stored under this key");
        }
        return key;
    }

    @Override
    public byte[] get(String key, String contentType) {
        byte[] content = images.get(key);
        if (content == null) {
            throw new ProofStorageException("The image is not in memory (the backend may have restarted)");
        }
        return content.clone();
    }

    @Override
    public void delete(String key, String contentType) {
        images.remove(key);
    }

    /** For tests: how many images are kept. */
    public int size() {
        return images.size();
    }

    /** For tests: whether an image is kept under this key. */
    public boolean contains(String key) {
        return images.containsKey(key);
    }
}
