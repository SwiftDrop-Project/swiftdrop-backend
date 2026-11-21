package com.swiftdrop.courier.internal.application;

import com.swiftdrop.shared.error.FieldValidationException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/**
 * Checks a proof-of-delivery photo (a photo of the delivery slip the recipient signed) before it is stored, and removes
 * the metadata it doesn't need:
 * <ul>
 *   <li>the type comes from the file's first bytes, never from its name or the declared content type: JPEG, PNG or
 *   WebP only;</li>
 *   <li>at most 5 MB, at most 12,000 px on a side and 40 megapixels (checked from the header before decoding, so a
 *   "decompression bomb" is refused cheaply), and a real image: JPEG and PNG are decoded all the way through at a
 *   reduced size; WebP is checked from its header (the JDK has no WebP decoder);</li>
 *   <li>EXIF (camera, time, GPS location), XMP, comments and text chunks are removed without re-encoding the image,
 *   so the photo keeps its quality. The courier screens draw the photo upright before uploading it, because the
 *   orientation tag goes with EXIF.</li>
 * </ul>
 * Errors are reported on the {@code file} field, with the courier's words.
 */
final class ProofImages {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    /** A side longer than this isn't a phone photo of a slip. */
    static final int MAX_SIDE = 12_000;
    /** At most this many pixels (the courier screens send at most 2560 px on the longer side). */
    static final long MAX_PIXELS = 40_000_000L;
    /** JPEG and PNG are fully decoded to check them, at most this many pixels on the longer side (subsampled). */
    private static final int CHECK_SIDE = 1024;

    static final String REQUIRED = "Add a photo of the signed delivery slip.";
    static final String TOO_LARGE = "Use a photo up to 5 MB.";
    static final String WRONG_TYPE = "Use a JPEG, PNG or WebP photo.";
    static final String UNREADABLE = "This photo can't be read. Take it again.";

    record Checked(String contentType, byte[] content) {
    }

    private ProofImages() {
    }

    /**
     * The photo as it will be stored: its real content type and its bytes without metadata.
     *
     * @throws FieldValidationException field {@code file}
     */
    static Checked check(byte[] content) {
        if (content == null || content.length == 0) {
            throw invalid(REQUIRED);
        }
        if (content.length > MAX_BYTES) {
            throw invalid(TOO_LARGE);
        }
        String type = sniff(content);
        if (type == null) {
            throw invalid(WRONG_TYPE);
        }
        try {
            byte[] stripped = switch (type) {
                case "image/jpeg" -> stripJpeg(content);
                case "image/png" -> stripPng(content);
                default -> stripWebp(content);
            };
            if ("image/webp".equals(type)) {
                // The JDK has no WebP decoder: the header gives the size; the courier screens send JPEG.
                requireSensibleSize(webpSize(stripped));
            } else {
                decodeSubsampled(stripped);
            }
            return new Checked(type, stripped);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof FieldValidationException invalid) {
                throw invalid;
            }
            throw invalid(UNREADABLE);
        }
    }

    /** JPEG, PNG or WebP from the magic bytes; null for anything else. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8), png)) {
            return "image/png";
        }
        if (b.length >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP")) {
            return "image/webp";
        }
        return null;
    }

    // ---- JPEG: drop APP1-APP15 (EXIF, XMP, ...) except the ICC colour profile, and COM ----

    static byte[] stripJpeg(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 2); // SOI
        int i = 2;
        while (i < in.length) {
            if ((in[i] & 0xFF) != 0xFF) {
                throw new IOException("Not a JPEG marker");
            }
            int marker = in[i + 1] & 0xFF;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xDA) { // start of scan: the image data follows, copy the rest as it is
                // A whole JPEG ends with EOI (FF D9), maybe followed by padding: a cut-off upload doesn't.
                int end = in.length;
                while (end > i && in[end - 1] == 0) {
                    end--;
                }
                if (end - i < 4 || (in[end - 2] & 0xFF) != 0xFF || (in[end - 1] & 0xFF) != 0xD9) {
                    throw new IOException("The JPEG is cut off");
                }
                out.write(in, i, in.length - i);
                return out.toByteArray();
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // no length
                out.write(in, i, 2);
                i += 2;
                continue;
            }
            if (marker == 0xD9) { // end of image before any scan
                throw new IOException("No image data");
            }
            int length = ((in[i + 2] & 0xFF) << 8) | (in[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > in.length) {
                throw new IOException("Bad JPEG segment");
            }
            boolean app = marker >= 0xE1 && marker <= 0xEF;
            boolean iccProfile = marker == 0xE2 && length >= 14 && ascii(in, i + 4, 11).equals("ICC_PROFILE");
            boolean drop = (app && !iccProfile) || marker == 0xFE;
            if (!drop) {
                out.write(in, i, 2 + length);
            }
            i += 2 + length;
        }
        throw new IOException("No image data");
    }

    // ---- PNG: drop text, EXIF and time chunks ----

    private static final Set<String> PNG_DROP = Set.of("tEXt", "zTXt", "iTXt", "eXIf", "tIME");

    static byte[] stripPng(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 8);
        int i = 8;
        boolean ended = false;
        while (i + 12 <= in.length) {
            long length = readIntBigEndian(in, i) & 0xFFFFFFFFL;
            String type = ascii(in, i + 4, 4);
            long total = 12 + length;
            if (length > Integer.MAX_VALUE || i + total > in.length) {
                throw new IOException("Bad PNG chunk");
            }
            if (!PNG_DROP.contains(type)) {
                out.write(in, i, (int) total);
            }
            i += (int) total;
            if (type.equals("IEND")) {
                ended = true;
                break;
            }
        }
        if (!ended) {
            throw new IOException("No PNG end");
        }
        return out.toByteArray();
    }

    // ---- WebP: drop the EXIF and XMP chunks and clear their flags ----

    static byte[] stripWebp(byte[] in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(in.length);
        body.write(in, 8, 4); // "WEBP"
        int i = 12;
        int end = Math.min(in.length, 8 + (int) Math.min(readIntLittleEndian(in, 4) & 0xFFFFFFFFL, in.length));
        while (i + 8 <= end) {
            String fourCc = ascii(in, i, 4);
            long size = readIntLittleEndian(in, i + 4) & 0xFFFFFFFFL;
            long padded = size + (size & 1);
            if (i + 8 + size > in.length) {
                throw new IOException("Bad WebP chunk");
            }
            int chunkEnd = (int) Math.min(in.length, i + 8 + padded);
            if (!fourCc.equals("EXIF") && !fourCc.equals("XMP ")) {
                byte[] chunk = Arrays.copyOfRange(in, i, chunkEnd);
                if (fourCc.equals("VP8X") && chunk.length > 8) {
                    chunk[8] = (byte) (chunk[8] & ~0x0C); // EXIF (0x08) and XMP (0x04) flags
                }
                body.write(chunk);
            }
            i = chunkEnd;
        }
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        out.write(new byte[]{'R', 'I', 'F', 'F'});
        int riffSize = payload.length;
        out.write(new byte[]{(byte) riffSize, (byte) (riffSize >> 8), (byte) (riffSize >> 16), (byte) (riffSize >> 24)});
        out.write(payload);
        return out.toByteArray();
    }

    /** Refuses a size that is empty, has a side over MAX_SIDE, or more than MAX_PIXELS pixels. */
    private static void requireSensibleSize(int[] size) {
        long width = size[0];
        long height = size[1];
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE || width * height > MAX_PIXELS) {
            throw invalid(UNREADABLE);
        }
    }

    /**
     * Checks a JPEG or PNG all the way through: the size from its header first (so a huge "decompression bomb" is
     * refused before decoding), then the whole image decoded with subsampling, at most CHECK_SIDE pixels on the longer
     * side, so a truncated or corrupt file is refused while memory stays small (about 4 MB).
     */
    private static void decodeSubsampled(byte[] content) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No reader");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                requireSensibleSize(new int[]{width, height});
                int step = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) CHECK_SIDE));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                if (reader.read(0, param) == null) {
                    throw new IOException("Nothing decoded");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /** Width and height of a WebP from its VP8X, VP8 or VP8L header. */
    static int[] webpSize(byte[] in) throws IOException {
        int i = 12;
        while (i + 8 <= in.length) {
            String fourCc = ascii(in, i, 4);
            long size = readIntLittleEndian(in, i + 4) & 0xFFFFFFFFL;
            int data = i + 8;
            switch (fourCc) {
                case "VP8X" -> {
                    if (data + 10 > in.length) throw new IOException("Short VP8X");
                    return new int[]{1 + read24LittleEndian(in, data + 4), 1 + read24LittleEndian(in, data + 7)};
                }
                case "VP8 " -> {
                    if (data + 10 > in.length || (in[data + 3] & 0xFF) != 0x9D || (in[data + 4] & 0xFF) != 0x01
                            || (in[data + 5] & 0xFF) != 0x2A) {
                        throw new IOException("Bad VP8");
                    }
                    int width = ((in[data + 6] & 0xFF) | ((in[data + 7] & 0xFF) << 8)) & 0x3FFF;
                    int height = ((in[data + 8] & 0xFF) | ((in[data + 9] & 0xFF) << 8)) & 0x3FFF;
                    return new int[]{width, height};
                }
                case "VP8L" -> {
                    if (data + 5 > in.length || (in[data] & 0xFF) != 0x2F) throw new IOException("Bad VP8L");
                    int bits = (in[data + 1] & 0xFF) | ((in[data + 2] & 0xFF) << 8) | ((in[data + 3] & 0xFF) << 16)
                            | ((in[data + 4] & 0xFF) << 24);
                    return new int[]{(bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1};
                }
                default -> i = (int) Math.min(in.length, data + size + (size & 1));
            }
        }
        throw new IOException("No WebP image");
    }

    private static FieldValidationException invalid(String message) {
        return new FieldValidationException("file", message);
    }

    private static String ascii(byte[] b, int from, int length) {
        if (from < 0 || from + length > b.length) {
            return "";
        }
        return new String(b, from, length, StandardCharsets.US_ASCII);
    }

    private static int readIntBigEndian(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private static int readIntLittleEndian(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }

    private static int read24LittleEndian(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16);
    }
}
