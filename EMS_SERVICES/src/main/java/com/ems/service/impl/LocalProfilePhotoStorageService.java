package com.ems.service.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ems.exception.BusinessException;
import com.ems.service.ProfilePhotoContent;
import com.ems.service.ProfilePhotoStorageService;

@Service
@Profile("!prod")
public class LocalProfilePhotoStorageService implements ProfilePhotoStorageService {

	private static final long DEFAULT_MAX_SIZE_BYTES = 5L * 1024L * 1024L;
	private static final int DEFAULT_MAX_DIMENSION = 4096;
	private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION = Map.of(
			"image/jpeg", "jpg",
			"image/png", "png",
			"image/webp", "webp");

	@Value("${app.storage.profile-photo.directory:storage/profile-photos}")
	private String storageDirectory;

	@Value("${app.storage.profile-photo.max-size-bytes:5242880}")
	private long maxSizeBytes;

	@Value("${app.storage.profile-photo.max-width:4096}")
	private int maxWidth;

	@Value("${app.storage.profile-photo.max-height:4096}")
	private int maxHeight;

	@Override
	public String storeProfilePhoto(String rawProfilePhoto, String ownerHint) {
		if (rawProfilePhoto == null || rawProfilePhoto.isBlank()) {
			return null;
		}

		ParsedProfilePhoto parsed = parseAndValidate(rawProfilePhoto);
		Path baseDir = Paths.get(storageDirectory).toAbsolutePath().normalize();
		String sanitizedOwnerHint = ownerHint == null ? "user" : ownerHint.replaceAll("[^A-Za-z0-9_-]", "");
		String fileName = sanitizedOwnerHint + "-" + UUID.randomUUID() + "." + parsed.extension();
		Path targetPath = baseDir.resolve(fileName);

		try {
			Files.createDirectories(baseDir);
			Files.copy(new ByteArrayInputStream(parsed.bytes()), targetPath, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			throw new BusinessException("Failed to store profile photo", HttpStatus.INTERNAL_SERVER_ERROR);
		}

		return fileName;
	}

	@Override
	public ProfilePhotoContent loadProfilePhoto(String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			throw new BusinessException("Profile photo not found", HttpStatus.NOT_FOUND);
		}

		try {
			Path filePath = Paths.get(storageDirectory).toAbsolutePath().normalize().resolve(storageKey).normalize();
			Resource resource = new UrlResource(filePath.toUri());
			if (!resource.exists()) {
				throw new BusinessException("Profile photo not found", HttpStatus.NOT_FOUND);
			}

			String contentType = Files.probeContentType(filePath);
			if (contentType == null) {
				contentType = detectContentTypeFromFileName(storageKey);
			}
			return new ProfilePhotoContent(resource, contentType, storageKey);
		} catch (IOException ex) {
			throw new BusinessException("Failed to load profile photo", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@Override
	public void deleteProfilePhoto(String storageKey) {
		if (!isStoredReference(storageKey)) {
			return;
		}

		try {
			Path filePath = Paths.get(storageDirectory).toAbsolutePath().normalize().resolve(storageKey).normalize();
			Files.deleteIfExists(filePath);
		} catch (IOException ex) {
			throw new BusinessException("Failed to delete profile photo", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@Override
	public boolean isStoredReference(String value) {
		return value != null && !value.isBlank() && !value.startsWith("data:") && !value.contains(";base64,");
	}

	@Override
	public String resolveAccessUrl(String storageKey) {
		// Must match UserProfileController's mapping; /api/users/profile/photo
		// was never served and resolved to a dead link on every profile read.
		return storageKey == null ? null : "/api/users/me/photo";
	}

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
