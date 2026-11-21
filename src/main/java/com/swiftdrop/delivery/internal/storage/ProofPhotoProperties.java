package com.swiftdrop.delivery.internal.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * Proof-of-delivery photo storage ({@code swiftdrop.delivery.proof-photos}, from PROOF_STORAGE_PROVIDER,
 * CLOUDINARY_URL, CLOUDINARY_PROOF_FOLDER):
 *
 * @param storage          {@code cloudinary} (private Cloudinary assets; needs {@code cloudinaryUrl}), {@code memory}
 *                         (only when chosen explicitly: tests and local runs; images are lost on restart) or unset
 *                         (= {@code disabled}: uploads answer 503 and no parcel can be delivered; never a silent
 *                         fallback to memory)
 * @param cloudinaryUrl    {@code cloudinary://<api key>:<api secret>@<cloud name>}; never logged
 * @param folder           Cloudinary folder for the images
 * @param orphanMaxAge     an uploaded photo that was never attached is removed after this long
 * @param orphanSweepEnabled whether the hourly orphan clean-up runs
 */
@ConfigurationProperties("swiftdrop.delivery.proof-photos")
public record ProofPhotoProperties(String storage, String cloudinaryUrl, String folder, Duration orphanMaxAge,
                                   Boolean orphanSweepEnabled) {

    public static final String MEMORY = "memory";
    public static final String CLOUDINARY = "cloudinary";
    public static final String DISABLED = "disabled";

    public ProofPhotoProperties {
        storage = storage == null || storage.isBlank() ? DISABLED : storage.trim().toLowerCase(Locale.ROOT);
        cloudinaryUrl = cloudinaryUrl == null ? "" : cloudinaryUrl.trim();
        folder = folder == null || folder.isBlank() ? "swiftdrop/proof-of-delivery" : folder.trim();
        orphanMaxAge = orphanMaxAge == null ? Duration.ofHours(24) : orphanMaxAge;
        orphanSweepEnabled = orphanSweepEnabled == null || orphanSweepEnabled;
    }

    /** Keeps the Cloudinary credentials out of logs and error pages. */
    @Override
    public String toString() {
        return "ProofPhotoProperties[storage=" + storage + ", folder=" + folder + ", orphanMaxAge=" + orphanMaxAge
                + ", orphanSweepEnabled=" + orphanSweepEnabled + "]";
    }
}
