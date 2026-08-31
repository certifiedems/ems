package com.ems.service.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.ems.exception.BusinessException;
import com.ems.service.ProfilePhotoContent;
import com.ems.service.ProfilePhotoStorageService;

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
 * R2 (S3-compatible) implementation of {@link ProfilePhotoStorageService}.
 *
 * <p>Same validation rules as the previous local-disk implementation
 * (magic-byte content sniffing, size/dimension limits) — only the storage
 * backend changes. Photos are kept in a private bucket and served through
 * the existing authenticated {@code /api/users/me/photo} endpoint, so no
 * public bucket access or signed URLs are needed.</p>
 */
@Service
@Profile("prod")
public class R2ProfilePhotoStorageService implements ProfilePhotoStorageService {

    private static final long DEFAULT_MAX_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final int DEFAULT_MAX_DIMENSION = 4096;
    private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    @Value("${app.storage.r2.account-id}")
    private String accountId;

    @Value("${app.storage.r2.access-key}")
    private String accessKey;

    @Value("${app.storage.r2.secret-key}")
    private String secretKey;

    @Value("${app.storage.r2.bucket:ems-profile-photos}")
    private String bucket;

    @Value("${app.storage.profile-photo.max-size-bytes:5242880}")
    private long maxSizeBytes;

    @Value("${app.storage.profile-photo.max-width:4096}")
    private int maxWidth;

    @Value("${app.storage.profile-photo.max-height:4096}")
    private int maxHeight;

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
    public String storeProfilePhoto(String rawProfilePhoto, String ownerHint) {
        if (rawProfilePhoto == null || rawProfilePhoto.isBlank()) {
            return null;
        }

        ParsedProfilePhoto parsed = parseAndValidate(rawProfilePhoto);
        String sanitizedOwnerHint = ownerHint == null ? "user" : ownerHint.replaceAll("[^A-Za-z0-9_-]", "");
        String objectKey = sanitizedOwnerHint + "-" + UUID.randomUUID() + "." + parsed.extension();

        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(parsed.contentType())
                            .build(),
                    RequestBody.fromBytes(parsed.bytes()));
        } catch (S3Exception ex) {
            throw new BusinessException("Failed to store profile photo", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return objectKey;
    }

    @Override
    public ProfilePhotoContent loadProfilePhoto(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException("Profile photo not found", HttpStatus.NOT_FOUND);
        }

        try (ResponseInputStream<GetObjectResponse> stream = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(storageKey).build())) {

            byte[] bytes = stream.readAllBytes();
            String contentType = stream.response().contentType();
            if (contentType == null) {
                contentType = detectContentTypeFromFileName(storageKey);
            }

            Resource resource = new ByteArrayResource(bytes);
            return new ProfilePhotoContent(resource, contentType, storageKey);
        } catch (NoSuchKeyException ex) {
            throw new BusinessException("Profile photo not found", HttpStatus.NOT_FOUND);
        } catch (S3Exception | IOException ex) {
            throw new BusinessException("Failed to load profile photo", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void deleteProfilePhoto(String storageKey) {
        if (!isStoredReference(storageKey)) {
            return;
        }

        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (S3Exception ex) {
            throw new BusinessException("Failed to delete profile photo", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public boolean isStoredReference(String value) {
        return value != null && !value.isBlank() && !value.startsWith("data:") && !value.contains(";base64,");
    }

    @Override
    public String resolveAccessUrl(String storageKey) {
        // Same pattern as before: served through the authenticated backend
        // endpoint, not a direct R2 URL, so access control stays consistent.
        return storageKey == null ? null : "/api/users/me/photo";
    }

    // --- validation logic below is unchanged from LocalProfilePhotoStorageService ---

    private ParsedProfilePhoto parseAndValidate(String rawProfilePhoto) {
        String normalized = rawProfilePhoto.trim();
        String payload = normalized;
        String declaredContentType = null;

        if (normalized.startsWith("data:")) {
            int commaIndex = normalized.indexOf(',');
            if (commaIndex <= 0) {
                throw new BusinessException("Invalid base64 image format", HttpStatus.BAD_REQUEST);
            }

            String metadata = normalized.substring(5, commaIndex);
            String[] metadataParts = metadata.split(";");
            declaredContentType = metadataParts[0].toLowerCase();
            if (!metadata.toLowerCase().contains(";base64")) {
                throw new BusinessException("Profile photo must be base64 encoded", HttpStatus.BAD_REQUEST);
            }
            payload = normalized.substring(commaIndex + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Profile photo must be a valid base64-encoded image", HttpStatus.BAD_REQUEST);
        }

        long allowedMaxSize = maxSizeBytes > 0 ? maxSizeBytes : DEFAULT_MAX_SIZE_BYTES;
        if (bytes.length > allowedMaxSize) {
            throw new BusinessException("Profile photo size must not exceed 5 MB", HttpStatus.BAD_REQUEST);
        }

        String detectedContentType = detectContentType(bytes);
        if (!CONTENT_TYPE_TO_EXTENSION.containsKey(detectedContentType)) {
            throw new BusinessException("Only JPEG, PNG, and WEBP images are allowed", HttpStatus.BAD_REQUEST);
        }

        if (declaredContentType != null && !declaredContentType.equals(detectedContentType)) {
            throw new BusinessException("Profile photo content type does not match image payload", HttpStatus.BAD_REQUEST);
        }

        validateImageDimensions(bytes, detectedContentType);
        return new ParsedProfilePhoto(bytes, detectedContentType, CONTENT_TYPE_TO_EXTENSION.get(detectedContentType));
    }

    private String detectContentType(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }

        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && (bytes[4] & 0xFF) == 0x0D
                && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0x0A) {
            return "image/png";
        }

        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }

        return "unsupported";
    }

    private void validateImageDimensions(byte[] bytes, String contentType) {
        if ("image/webp".equals(contentType)) {
            return;
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new BusinessException("Invalid image payload", HttpStatus.BAD_REQUEST);
            }

            int allowedMaxWidth = maxWidth > 0 ? maxWidth : DEFAULT_MAX_DIMENSION;
            int allowedMaxHeight = maxHeight > 0 ? maxHeight : DEFAULT_MAX_DIMENSION;
            if (image.getWidth() > allowedMaxWidth || image.getHeight() > allowedMaxHeight) {
                throw new BusinessException("Profile photo dimensions exceed allowed limits", HttpStatus.BAD_REQUEST);
            }
        } catch (IOException ex) {
            throw new BusinessException("Invalid image payload", HttpStatus.BAD_REQUEST);
        }
    }

    private String detectContentTypeFromFileName(String fileName) {
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private record ParsedProfilePhoto(byte[] bytes, String contentType, String extension) {
    }
}
