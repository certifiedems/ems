package com.ems.service.impl;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.ems.exception.BusinessException;
import com.ems.service.ProctorEvidenceContent;
import com.ems.service.ProctorEvidenceStorageService;

import lombok.extern.slf4j.Slf4j;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * R2 (S3-compatible) implementation of {@link ProctorEvidenceStorageService}.
 *
 * <p>Client wiring follows {@link R2ProfilePhotoStorageService} — same endpoint
 * shape, same {@code auto} region placeholder, same static credentials — but
 * reads its own {@code app.storage.r2.evidence.*} properties and therefore its
 * own bucket and API token. The separation is deliberate: evidence is
 * unbounded-growth personal data that wants a stricter token and its own
 * lifecycle rule, while profile photos are bounded by user count and kept
 * indefinitely. Sharing one bucket would force one retention policy onto both.</p>
 *
 * <p>Objects are keyed {@code sessions/<sessionId>/<violationId>-<uuid>.<ext>} so
 * an entire attempt's evidence can be swept or listed by prefix.</p>
 */
@Slf4j
@Service
@Profile("prod")
public class R2ProctorEvidenceStorageService implements ProctorEvidenceStorageService {

    private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    private static final String DEFAULT_MEDIA_TYPE = "image/jpeg";
    private static final String DEFAULT_EXTENSION = "jpg";

    @Value("${app.storage.r2.evidence.account-id}")
    private String accountId;

    @Value("${app.storage.r2.evidence.access-key}")
    private String accessKey;

    @Value("${app.storage.r2.evidence.secret-key}")
    private String secretKey;

    @Value("${app.storage.r2.evidence.bucket:ems-proctoring-evidence}")
    private String bucket;

    /**
     * Kill switch. Set false to make the write path fall back to inline base64
     * without a redeploy if R2 is unreachable or the token is revoked.
     */
    @Value("${app.storage.r2.evidence.enabled:true}")
    private boolean objectStorageEnabled;

    private S3Client client;

    @PostConstruct
    void openClient() {
        client = S3Client.builder()
                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                // R2 ignores the region value but the SDK requires one to be set.
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    @PreDestroy
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public boolean isObjectStorageEnabled() {
        return objectStorageEnabled;
    }

    @Override
    public String storeEvidence(byte[] frameBytes, String mediaType, Long sessionId, Long violationId) {
        if (frameBytes == null || frameBytes.length == 0) {
            return null;
        }

        String normalizedMediaType = normalizeMediaType(mediaType);
        String objectKey = "sessions/" + sessionId + "/" + violationId + "-" + UUID.randomUUID()
                + "." + CONTENT_TYPE_TO_EXTENSION.getOrDefault(normalizedMediaType, DEFAULT_EXTENSION);

        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(normalizedMediaType)
                            .build(),
                    RequestBody.fromBytes(frameBytes));
        } catch (S3Exception ex) {
            throw new BusinessException("Failed to store proctoring evidence", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return objectKey;
    }

    @Override
    public ProctorEvidenceContent loadEvidence(String objectStorageKey) {
        if (objectStorageKey == null || objectStorageKey.isBlank()) {
            throw new BusinessException("Proctoring evidence not found", HttpStatus.NOT_FOUND);
        }

        try (ResponseInputStream<GetObjectResponse> stream = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(objectStorageKey).build())) {

            byte[] bytes = stream.readAllBytes();
            String contentType = stream.response().contentType();
            if (contentType == null) {
                contentType = detectMediaTypeFromKey(objectStorageKey);
            }
            return new ProctorEvidenceContent(bytes, contentType, objectStorageKey);
        } catch (NoSuchKeyException ex) {
            // Expected once a lifecycle rule has aged the frame out; the metadata
            // row outlives the object.
            throw new BusinessException("Proctoring evidence is no longer available", HttpStatus.NOT_FOUND);
        } catch (S3Exception | IOException ex) {
            throw new BusinessException("Failed to load proctoring evidence", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void deleteEvidence(String objectStorageKey) {
        if (objectStorageKey == null || objectStorageKey.isBlank()) {
            return;
        }

        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectStorageKey).build());
        } catch (S3Exception ex) {
            throw new BusinessException("Failed to delete proctoring evidence", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String normalizeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return DEFAULT_MEDIA_TYPE;
        }
        String normalized = mediaType.toLowerCase(Locale.ROOT);
        return CONTENT_TYPE_TO_EXTENSION.containsKey(normalized) ? normalized : DEFAULT_MEDIA_TYPE;
    }

    private String detectMediaTypeFromKey(String objectKey) {
        if (objectKey.endsWith(".png")) {
            return "image/png";
        }
        if (objectKey.endsWith(".webp")) {
            return "image/webp";
        }
        return DEFAULT_MEDIA_TYPE;
    }
}
