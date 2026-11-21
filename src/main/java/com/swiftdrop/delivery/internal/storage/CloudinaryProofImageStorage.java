package com.swiftdrop.delivery.internal.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Proof images in Cloudinary, stored as {@code authenticated} (private) image assets: they have no public URL, and
 * Cloudinary serves them only through a signed, short-lived download link that this class makes and uses on the
 * server. The database keeps the durable public id ({@code <folder>/<photo id>}), never a URL.
 *
 * <p>Credentials come from CLOUDINARY_URL and never leave the backend; nothing here logs them, the public id or the
 * image.
 */
public class CloudinaryProofImageStorage implements ProofImageStorage {

    public static final String NAME = "CLOUDINARY";

    /** Delivery type for private assets: reachable only with a signature. */
    static final String TYPE = "authenticated";

    /** How long the server-side download link is valid. */
    private static final Duration DOWNLOAD_LINK_LIFETIME = Duration.ofSeconds(60);

    /** No proof image is bigger than this; a larger answer is refused. */
    private static final long MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;

    private final Cloudinary cloudinary;
    private final String folder;
    private final HttpClient http;
    private final Clock clock;

    public CloudinaryProofImageStorage(Cloudinary cloudinary, String folder) {
        this(cloudinary, folder, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), Clock.systemUTC());
    }

    CloudinaryProofImageStorage(Cloudinary cloudinary, String folder, HttpClient http, Clock clock) {
        this.cloudinary = cloudinary;
        this.folder = folder.replaceAll("^/+|/+$", "");
        this.http = http;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Uploads the image as {@code <folder>/<key>}. The folder is sent twice on purpose: in the public id (the durable
     * reference the database keeps, used to read and delete the image) and as {@code asset_folder}, which is what
     * places the asset in that folder when the product environment uses dynamic folders (there the public id's path is
     * only a name, and without {@code asset_folder} the asset would land in the root folder, outside the folder the API
     * key is allowed to use).
     */
    @Override
    public String put(String key, byte[] content, String contentType) {
        String publicId = folder.isEmpty() ? key : folder + "/" + key;
        Map<String, Object> options = new HashMap<>(ObjectUtils.asMap(
                "public_id", publicId,
                "type", TYPE,
                "resource_type", "image",
                "overwrite", false,
                "unique_filename", false,
                "use_filename", false,
                "invalidate", false));
        if (!folder.isEmpty()) {
            options.put("asset_folder", folder);
        }
        try {
            Map<?, ?> result = cloudinary.uploader().upload(content, options);
            Object stored = result.get("public_id");
            if (!(stored instanceof String id) || id.isBlank()) {
                throw new ProofStorageException("Cloudinary didn't return the stored image's id");
            }
            // Never keep a proof that ended up public: it must be a private ("authenticated") image.
            if (!TYPE.equals(result.get("type")) || !"image".equals(result.get("resource_type"))) {
                try {
                    cloudinary.uploader().destroy(id, ObjectUtils.asMap("type", result.get("type"),
                            "resource_type", result.get("resource_type"), "invalidate", true));
                } catch (IOException | RuntimeException ignored) {
                    // Reported below; the asset id is in Cloudinary's console if it needs removing by hand.
                }
                throw new ProofStorageException("Cloudinary didn't store the image as a private asset");
            }
            return id;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ProofStorageException storage) {
                throw storage;
            }
            throw new ProofStorageException("Cloudinary upload failed: " + CloudinaryErrors.describe(ex), ex);
        }
    }

    @Override
    public byte[] get(String key, String contentType) {
        String link;
        try {
            link = cloudinary.privateDownload(key, format(contentType), ObjectUtils.asMap(
                    "type", TYPE,
                    "resource_type", "image",
                    "expires_at", clock.instant().plus(DOWNLOAD_LINK_LIFETIME).getEpochSecond()));
        } catch (Exception ex) {
            throw new ProofStorageException("Couldn't sign the Cloudinary download: " + CloudinaryErrors.describe(ex),
                    ex);
        }
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(link)).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ProofStorageException("Cloudinary download answered HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length == 0 || body.length > MAX_DOWNLOAD_BYTES) {
                throw new ProofStorageException("Cloudinary download had an unexpected size");
            }
            return body;
        } catch (IOException ex) {
            throw new ProofStorageException("Cloudinary download failed: " + CloudinaryErrors.describe(ex), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProofStorageException("Cloudinary download was interrupted", ex);
        }
    }

    @Override
    public void delete(String key, String contentType) {
        try {
            cloudinary.uploader().destroy(key, ObjectUtils.asMap(
                    "type", TYPE,
                    "resource_type", "image",
                    "invalidate", true));
        } catch (IOException | RuntimeException ex) {
            throw new ProofStorageException("Cloudinary delete failed: " + CloudinaryErrors.describe(ex), ex);
        }
    }

    /** Cloudinary's format name for a stored content type. */
    static String format(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new ProofStorageException("Unsupported proof image type");
        };
    }
}
