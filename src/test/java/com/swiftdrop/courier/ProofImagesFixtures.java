package com.swiftdrop.courier;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Test images for proof of delivery: metadata segments and chunks added to real images, and a minimal WebP. */
public final class ProofImagesFixtures {

    private ProofImagesFixtures() {
    }

    /** {@code jpeg} with an EXIF APP1 segment (camera and GPS words), a comment and an ICC profile after the SOI. */
    public static byte[] jpegWithExif(byte[] jpeg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        segment(out, 0xE1, "Exif\0\0Camera Model X GPS 6.9271N 79.8612E");
        segment(out, 0xFE, "Taken at 12 Galle Road");
        segment(out, 0xE2, "ICC_PROFILE\0\1\1colour data");
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** {@code png} with a tEXt chunk (a location) and an eXIf chunk inserted after IHDR. */
    public static byte[] pngWithText(byte[] png) {
        int afterIhdr = 8 + 12 + 13; // signature + IHDR chunk (length, type, 13 data bytes, CRC)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        chunk(out, "tEXt", "Location\0Colombo 03");
        chunk(out, "eXIf", "MM\0*GPS");
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }

    /** A minimal lossless WebP (VP8L) of {@code width} x {@code height}, optionally with VP8X, EXIF and XMP chunks. */
    public static byte[] webp(int width, int height, boolean withMetadata) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        if (withMetadata) {
            byte[] vp8x = new byte[10];
            vp8x[0] = 0x0C; // EXIF + XMP flags
            put24(vp8x, 4, width - 1);
            put24(vp8x, 7, height - 1);
            riffChunk(body, "VP8X", vp8x);
        }
        int bits = (width - 1) | ((height - 1) << 14);
        byte[] vp8l = {0x2F, (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24), 0, 0};
        riffChunk(body, "VP8L", vp8l);
        if (withMetadata) {
            riffChunk(body, "EXIF", "MM\0*GPS 6.9N".getBytes(StandardCharsets.US_ASCII));
            riffChunk(body, "XMP ", "<x:xmpmeta>camera</x:xmpmeta>".getBytes(StandardCharsets.US_ASCII));
        }
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(littleEndian(payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, String data) {
        byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
        int length = bytes.length + 2;
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(bytes);
    }

    private static void chunk(ByteArrayOutputStream out, String type, String data) {
        byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(new byte[]{(byte) (bytes.length >> 24), (byte) (bytes.length >> 16), (byte) (bytes.length >> 8),
                (byte) bytes.length});
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(bytes);
        out.writeBytes(new byte[4]); // CRC: not checked by the stripper
    }

    private static void riffChunk(ByteArrayOutputStream out, String fourCc, byte[] data) {
        out.writeBytes(fourCc.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(littleEndian(data.length));
        out.writeBytes(data);
        if ((data.length & 1) == 1) {
            out.write(0);
        }
    }

    private static byte[] littleEndian(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static void put24(byte[] target, int at, int value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >> 8);
        target[at + 2] = (byte) (value >> 16);
    }
}
