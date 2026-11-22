package com.swiftdrop.delivery.internal.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proof image storage without any network: the configuration choices and the Cloudinary adapter against a mocked SDK
 * and HTTP client (nothing is ever uploaded from tests).
 */
class ProofStorageTest {

    private static final String FAKE_URL = "cloudinary://123456789012345:test-secret-not-real@demo-cloud";

    private static ProofPhotoProperties properties(String storage, String url) {
        return new ProofPhotoProperties(storage, url, null, null, null);
    }

    @Test
    void unsetMeansNoStorageNeverASilentFallbackToMemory() {
        ProofStorageConfiguration configuration = new ProofStorageConfiguration();

        for (String unset : new String[]{null, "", "  "}) {
            ProofImageStorage storage = configuration.proofImageStorage(properties(unset, null));
            assertThat(storage).isInstanceOf(DisabledProofImageStorage.class);
            assertThatThrownBy(() -> storage.put("key", new byte[]{1}, "image/jpeg"))
                    .isInstanceOf(ProofStorageException.class);
            assertThatThrownBy(() -> storage.get("key", "image/jpeg")).isInstanceOf(ProofStorageException.class);
        }
        // Even a Cloudinary URL alone doesn't choose a storage: the provider has to be named.
        assertThat(configuration.proofImageStorage(properties(null, FAKE_URL)))
                .isInstanceOf(DisabledProofImageStorage.class);
    }

    @Test
    void memoryOnlyWhenChosenAndCloudinaryNeedsAFullUrl() {
        ProofStorageConfiguration configuration = new ProofStorageConfiguration();

        assertThat(configuration.proofImageStorage(properties("memory", null)))
                .isInstanceOf(InMemoryProofImageStorage.class);
        assertThat(configuration.proofImageStorage(properties("MEMORY", null)))
                .isInstanceOf(InMemoryProofImageStorage.class);
        assertThat(configuration.proofImageStorage(properties("cloudinary", FAKE_URL)))
                .isInstanceOf(CloudinaryProofImageStorage.class);
        // Cloudinary named but not (fully) configured: the backend refuses to start, it never falls back to memory.
        for (String bad : new String[]{"", "https://example.com", "cloudinary://@demo-cloud"}) {
            assertThatThrownBy(() -> configuration.proofImageStorage(properties("cloudinary", bad)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("test-secret-not-real");
        }
        assertThatThrownBy(() -> configuration.proofImageStorage(properties("s3", null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCredentialsNeverAppearInTheProperties() {
        assertThat(properties("cloudinary", FAKE_URL).toString()).doesNotContain("test-secret-not-real")
                .doesNotContain("123456789012345");
    }

    @Test
    void imagesAreUploadedAsPrivateAuthenticatedAssetsWithoutOverwriting() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of("public_id", "swiftdrop/pod/abc",
                "type", "authenticated", "resource_type", "image"));
        CloudinaryProofImageStorage storage = new CloudinaryProofImageStorage(cloudinary, "/swiftdrop/pod/");

        assertThat(storage.put("abc", new byte[]{1}, "image/jpeg")).isEqualTo("swiftdrop/pod/abc");

        verify(uploader).upload(any(), argThat(options -> "authenticated".equals(options.get("type"))
                && "swiftdrop/pod/abc".equals(options.get("public_id"))
                && Boolean.FALSE.equals(options.get("overwrite"))
                && "image".equals(options.get("resource_type"))));
    }

    @Test
    void theImageIsPlacedInTheConfiguredAssetFolderAndNamedWithTheSamePath() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of("public_id",
                "swiftdrop-local/proof-of-delivery/3f1d", "type", "authenticated", "resource_type", "image"));

        String stored = new CloudinaryProofImageStorage(cloudinary, "/swiftdrop-local/proof-of-delivery/")
                .put("3f1d", new byte[]{1}, "image/jpeg");

        // Dynamic folder mode: asset_folder decides the folder; the public id (kept in the database) has the same path.
        assertThat(stored).isEqualTo("swiftdrop-local/proof-of-delivery/3f1d");
        verify(uploader).upload(any(), argThat(options ->
                "swiftdrop-local/proof-of-delivery".equals(options.get("asset_folder"))
                        && "swiftdrop-local/proof-of-delivery/3f1d".equals(options.get("public_id"))
                        && "authenticated".equals(options.get("type"))));
    }

    @Test
    void withoutAFolderNoAssetFolderIsSent() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of("public_id", "abc", "type", "authenticated",
                "resource_type", "image"));

        new CloudinaryProofImageStorage(cloudinary, "").put("abc", new byte[]{1}, "image/jpeg");

        verify(uploader).upload(any(), argThat(options -> !options.containsKey("asset_folder")
                && "abc".equals(options.get("public_id"))));
    }

    @Test
    void aRefusedUploadSaysWhyWithoutTheEnvironmentIdOrAnyCredential() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        // What the SDK throws when Cloudinary refuses the API key's permission.
        when(uploader.upload(any(), anyMap())).thenThrow(new RuntimeException(
                "[prodenv:0123456789abcdef0123456789abcd] Request forbidden due to missing permissions (actions=[\"create\"])"));

        assertThatThrownBy(() -> new CloudinaryProofImageStorage(cloudinary, "f").put("abc", new byte[]{1}, "image/jpeg"))
                .isInstanceOf(ProofStorageException.class)
                .hasMessage("Cloudinary upload failed: permission denied (missing: create)");
    }

    @Test
    void networkAndDeleteFailuresAreDescribedSafely() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenThrow(new java.net.SocketTimeoutException("Read timed out"));
        when(uploader.destroy(any(), anyMap())).thenThrow(new RuntimeException("Invalid Signature 0a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3. "
                + "String to sign - 'public_id=f/abc&timestamp=1795000000'."));
        CloudinaryProofImageStorage storage = new CloudinaryProofImageStorage(cloudinary, "f");

        assertThatThrownBy(() -> storage.put("abc", new byte[]{1}, "image/jpeg"))
                .hasMessage("Cloudinary upload failed: network error (SocketTimeoutException)");
        assertThatThrownBy(() -> storage.delete("f/abc", "image/jpeg"))
                .hasMessage("Cloudinary delete failed: authentication failed (check the API key and secret)");
    }

    @Test
    void anImageThatEndedUpPublicIsDeletedAndRefused() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of("public_id", "swiftdrop/pod/abc",
                "type", "upload", "resource_type", "image"));

        assertThatThrownBy(() -> new CloudinaryProofImageStorage(cloudinary, "swiftdrop/pod").put("abc", new byte[]{1},
                "image/jpeg")).isInstanceOf(ProofStorageException.class);
        verify(uploader).destroy(eq("swiftdrop/pod/abc"), argThat(options -> "upload".equals(options.get("type"))));
    }

    @Test
    void aFailedUploadIsAStorageError() throws IOException {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenThrow(new IOException("network"));

        assertThatThrownBy(() -> new CloudinaryProofImageStorage(cloudinary, "f").put("abc", new byte[]{1}, "image/png"))
                .isInstanceOf(ProofStorageException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void imagesAreReadThroughAShortLivedSignedPrivateDownload() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        when(cloudinary.privateDownload(eq("swiftdrop/pod/abc"), eq("webp"), anyMap()))
                .thenReturn("https://api.cloudinary.com/v1_1/demo/image/download?signature=x");
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{9, 8, 7});
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
        CloudinaryProofImageStorage storage = new CloudinaryProofImageStorage(cloudinary, "swiftdrop/pod", http,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(storage.get("swiftdrop/pod/abc", "image/webp")).containsExactly(9, 8, 7);
        verify(cloudinary).privateDownload(eq("swiftdrop/pod/abc"), eq("webp"), argThat(options ->
                "authenticated".equals(options.get("type"))
                        && Long.valueOf(now.getEpochSecond() + 60).equals(options.get("expires_at"))));

        when(response.statusCode()).thenReturn(404);
        assertThatThrownBy(() -> storage.get("swiftdrop/pod/abc", "image/webp"))
                .isInstanceOf(ProofStorageException.class);
    }

    @Test
    void onlyTheThreeImageTypesHaveAFormat() {
        assertThat(CloudinaryProofImageStorage.format("image/jpeg")).isEqualTo("jpg");
        assertThat(CloudinaryProofImageStorage.format("image/png")).isEqualTo("png");
        assertThat(CloudinaryProofImageStorage.format("image/webp")).isEqualTo("webp");
        assertThatThrownBy(() -> CloudinaryProofImageStorage.format("image/gif"))
                .isInstanceOf(ProofStorageException.class);
    }
}
