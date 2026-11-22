package com.swiftdrop.courier.internal.application;

import com.swiftdrop.courier.ProofImagesFixtures;
import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The proof photo checks: real type from the bytes, size, a readable header, and metadata removed losslessly. */
class ProofImagesTest {

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static void refused(byte[] content, String message) {
        assertThatThrownBy(() -> ProofImages.check(content))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.fieldErrors()).containsEntry("file", message));
    }

    @Test
    void theTypeComesFromTheBytesNotTheName() throws IOException {
        assertThat(ProofImages.sniff(image("jpg", 4, 4))).isEqualTo("image/jpeg");
        assertThat(ProofImages.sniff(image("png", 4, 4))).isEqualTo("image/png");
        assertThat(ProofImages.sniff(ProofImagesFixtures.webp(4, 4, false))).isEqualTo("image/webp");
        assertThat(ProofImages.sniff(image("gif", 4, 4))).isNull();
        assertThat(ProofImages.sniff("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isNull();
    }

    @Test
    void emptyTooLargeWrongTypeAndBrokenImagesAreRefused() throws IOException {
        refused(null, ProofImages.REQUIRED);
        refused(new byte[0], ProofImages.REQUIRED);
        refused(Arrays.copyOf(image("jpg", 4, 4), (int) ProofImages.MAX_BYTES + 1), ProofImages.TOO_LARGE);
        refused(image("gif", 4, 4), ProofImages.WRONG_TYPE);
        refused("<svg onload=alert(1)>".getBytes(StandardCharsets.US_ASCII), ProofImages.WRONG_TYPE);
        // The right magic bytes, then garbage.
        refused(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02}, ProofImages.UNREADABLE);
        byte[] png = image("png", 4, 4);
        refused(Arrays.copyOf(png, 30), ProofImages.UNREADABLE);
    }

    @Test
    void aJpegLosesItsExifXmpAndCommentsButKeepsItsColourProfileAndPixels() throws IOException {
        byte[] original = image("jpg", 40, 30);
        byte[] withExif = ProofImagesFixtures.jpegWithExif(original);

        ProofImages.Checked checked = ProofImages.check(withExif);

        assertThat(checked.contentType()).isEqualTo("image/jpeg");
        assertThat(latin1(checked.content())).doesNotContain("Exif").doesNotContain("GPS").doesNotContain("Galle Road")
                .contains("ICC_PROFILE");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(checked.content()));
        assertThat(decoded.getWidth()).isEqualTo(40);
        assertThat(decoded.getHeight()).isEqualTo(30);
    }

    @Test
    void aPngLosesItsTextAndExifChunks() throws IOException {
        byte[] withText = ProofImagesFixtures.pngWithText(image("png", 12, 8));

        ProofImages.Checked checked = ProofImages.check(withText);

        assertThat(checked.contentType()).isEqualTo("image/png");
        assertThat(latin1(checked.content())).doesNotContain("Colombo").doesNotContain("eXIf").doesNotContain("tEXt");
        assertThat(ImageIO.read(new ByteArrayInputStream(checked.content())).getWidth()).isEqualTo(12);
    }

    @Test
    void aWebpLosesItsExifAndXmpChunksAndFlags() throws IOException {
        byte[] withMetadata = ProofImagesFixtures.webp(64, 48, true);

        ProofImages.Checked checked = ProofImages.check(withMetadata);

        assertThat(checked.contentType()).isEqualTo("image/webp");
        String text = latin1(checked.content());
        assertThat(text).doesNotContain("EXIF").doesNotContain("XMP ").doesNotContain("GPS").contains("VP8L");
        byte[] stripped = checked.content();
        int vp8x = text.indexOf("VP8X");
        assertThat(stripped[vp8x + 8] & 0x0C).as("EXIF and XMP flags cleared").isZero();
        int riffSize = (stripped[4] & 0xFF) | ((stripped[5] & 0xFF) << 8) | ((stripped[6] & 0xFF) << 16)
                | ((stripped[7] & 0xFF) << 24);
        assertThat(riffSize).isEqualTo(stripped.length - 8);
        assertThat(ProofImages.webpSize(stripped)).containsExactly(64, 48);
    }

    @Test
    void anImageWithAnAbsurdSizeIsRefusedWithoutDecodingIt() throws IOException {
        refused(ProofImagesFixtures.webp(16_000, 16_000, false), ProofImages.UNREADABLE);
        // Under the side limit but over 40 megapixels.
        refused(ProofImagesFixtures.webp(8_000, 8_000, false), ProofImages.UNREADABLE);
        // A tiny PNG whose header claims 20,000 x 20,000: refused from the header, before any decoding.
        byte[] png = image("png", 4, 4);
        byte[] bomb = png.clone();
        writeIntBigEndian(bomb, 16, 20_000); // IHDR width
        writeIntBigEndian(bomb, 20, 20_000); // IHDR height
        refused(bomb, ProofImages.UNREADABLE);
    }

    @Test
    void aCutOffOrCorruptImageIsRefusedEvenWithAGoodHeader() throws IOException {
        byte[] jpeg = image("jpg", 400, 300);
        refused(Arrays.copyOf(jpeg, jpeg.length - 200), ProofImages.UNREADABLE);
        byte[] png = image("png", 300, 200);
        byte[] corrupt = png.clone();
        // Scramble the compressed pixel data (IDAT), keeping the header and the end chunk.
        int idat = latin1(corrupt).indexOf("IDAT");
        for (int i = idat + 4; i < Math.min(corrupt.length - 16, idat + 60); i++) {
            corrupt[i] = (byte) 0x5A;
        }
        refused(corrupt, ProofImages.UNREADABLE);
    }

    @Test
    void aNormalPhoneSizedPhotoIsAccepted() throws IOException {
        ProofImages.Checked checked = ProofImages.check(image("jpg", 2560, 1920));
        assertThat(checked.contentType()).isEqualTo("image/jpeg");
        assertThat(ProofImages.check(image("png", 1200, 900)).contentType()).isEqualTo("image/png");
    }

    private static void writeIntBigEndian(byte[] target, int at, int value) {
        target[at] = (byte) (value >> 24);
        target[at + 1] = (byte) (value >> 16);
        target[at + 2] = (byte) (value >> 8);
        target[at + 3] = (byte) value;
    }
}
