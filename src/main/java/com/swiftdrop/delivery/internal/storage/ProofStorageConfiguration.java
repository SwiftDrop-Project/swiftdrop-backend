package com.swiftdrop.delivery.internal.storage;

import com.cloudinary.Cloudinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where proof-of-delivery images go ({@link ProofPhotoProperties#storage()}): private Cloudinary storage, this
 * backend's memory (only when PROOF_STORAGE_PROVIDER=memory is set explicitly: tests and local runs), or nothing when
 * it is unset (uploads fail with 503, so no parcel is delivered without durable proof; never a silent fallback to
 * memory). A wrong Cloudinary setting stops the backend at start-up, with a message that never repeats the
 * credentials.
 */
@Configuration(proxyBeanMethods = false)
class ProofStorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProofStorageConfiguration.class);

    @Bean
    ProofImageStorage proofImageStorage(ProofPhotoProperties properties) {
        return switch (properties.storage()) {
            case ProofPhotoProperties.CLOUDINARY -> cloudinary(properties);
            case ProofPhotoProperties.MEMORY -> {
                log.warn("PROOF_STORAGE_PROVIDER=memory: proof-of-delivery photos are kept in memory only and are lost "
                        + "when the backend stops. For tests and local runs only; use cloudinary everywhere else.");
                yield new InMemoryProofImageStorage();
            }
            case ProofPhotoProperties.DISABLED -> {
                log.error("No proof-of-delivery photo storage is configured: couriers can't mark parcels delivered "
                        + "(uploads answer 503). Set PROOF_STORAGE_PROVIDER=cloudinary with CLOUDINARY_URL, or "
                        + "PROOF_STORAGE_PROVIDER=memory for a local run.");
                yield new DisabledProofImageStorage();
            }
            default -> throw new IllegalStateException(
                    "PROOF_STORAGE_PROVIDER must be 'cloudinary', 'memory' or empty.");
        };
    }

    private static ProofImageStorage cloudinary(ProofPhotoProperties properties) {
        String url = properties.cloudinaryUrl();
        if (!url.startsWith("cloudinary://")) {
            throw new IllegalStateException(
                    "PROOF_STORAGE_PROVIDER=cloudinary needs CLOUDINARY_URL=cloudinary://<api key>:<api secret>@<cloud name>.");
        }
        Cloudinary cloudinary;
        try {
            cloudinary = new Cloudinary(url);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("CLOUDINARY_URL is not a valid Cloudinary URL.");
        }
        if (blank(cloudinary.config.cloudName) || blank(cloudinary.config.apiKey)
                || blank(cloudinary.config.apiSecret)) {
            throw new IllegalStateException(
                    "CLOUDINARY_URL must include the API key, the API secret and the cloud name.");
        }
        cloudinary.config.secure = true;
        log.info("Proof-of-delivery photos are stored as private Cloudinary assets.");
        return new CloudinaryProofImageStorage(cloudinary, properties.folder());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
