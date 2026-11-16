package com.swiftdrop.courier.internal.dto;

/** Proof of delivery: who received the parcel and whether a photo is attached. */
public record ProofResponse(String recipientName, boolean hasPhoto) {
}
