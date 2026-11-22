package com.swiftdrop.delivery;

/**
 * The image attached to a parcel's proof of delivery ({@link DeliveryApi#findProofPhoto}).
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 *
 * @param contentType e.g. {@code image/jpeg}
 * @param content     the image bytes
 */
public record ProofPhoto(String contentType, byte[] content) {
}
