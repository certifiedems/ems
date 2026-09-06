package com.ems.service.impl;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.response.CertificateResponse;
import com.ems.dto.response.CertificateVerificationResponse;
import com.ems.entity.Certificate;
import com.ems.entity.Certification;
import com.ems.entity.ExamAttempt;
import com.ems.entity.ExamSession;
import com.ems.entity.User;
import com.ems.enums.CertificateVerificationStatus;
import com.ems.enums.CertificationStatus;
import com.ems.enums.ResultStatus;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificateRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.ExamAttemptRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;
import com.ems.service.CertificateFileContent;
import com.ems.service.CertificatePdfGeneratorService;
import com.ems.service.CertificateService;
import com.ems.service.CertificateTemplate;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class CertificateServiceImpl implements CertificateService {

	private final CertificateRepository certificateRepository;
	private final CertificationRepository certificationRepository;
	private final ExamAttemptRepository examAttemptRepository;
	private final UserRepository userRepository;
	private final CertificatePdfGeneratorService certificatePdfGeneratorService;
	private final AuditService auditService;

	@Value("${app.storage.certificate.directory:storage/certificates}")
	private String certificateStorageDirectory;

	@Value("${app.certificate.verification-base-url:http://localhost:8080/api/certificates/verify}")
	private String verificationBaseUrl;

	@Override
	@CacheEvict(cacheNames = { "certificateVerification", "reports", "dashboard" }, allEntries = true)
	public CertificateResponse generateForSession(String email, Long sessionId) {
		ExamAttempt attempt = examAttemptRepository
				.findByExamSessionIdAndExamSessionUserEmailIgnoreCase(sessionId, email)
				.orElseThrow(() -> new ResourceNotFoundException("Exam result not found for session"));

		if (attempt.getResultStatus() != ResultStatus.PASS) {
			throw new BusinessException("Certificate can be generated only for PASS result", HttpStatus.BAD_REQUEST);
		}

		Optional<Certificate> existing = certificateRepository.findByExamAttempt(attempt);
		if (existing.isPresent()) {
			return toResponse(existing.get());
		}

		Certification certification = getOrCreateCertification(attempt);
		String certificateNumber = generateCertificateNumber(attempt);
		String verificationUrl = buildVerificationUrl(certificateNumber);

		byte[] qrCodePng = buildQrCodePng(verificationUrl);
		LocalDate issueDate = certification.getIssueDate();
		LocalDate expiryDate = certification.getExpiryDate();

		byte[] pdfBytes = certificatePdfGeneratorService.generateCertificatePdf(
				new CertificatePdfGeneratorService.CertificatePdfData(
						certificateNumber,
						candidateName(attempt.getExamSession()),
						attempt.getExamSession().getUser().getUserId(),
						certification.getCertificationLevel(),
						issueDate,
						expiryDate,
						verificationUrl,
						qrCodePng));

		String fileName = storePdf(certificateNumber, pdfBytes);
		Certificate certificate = Certificate.builder()
				.certificateNumber(certificateNumber)
				.certification(certification)
				.examAttempt(attempt)
				.certificateUrl(fileName)
				.qrCodeUrl("embedded:qr")
				.verificationUrl(verificationUrl)
				.issueDate(issueDate)
				.expiryDate(expiryDate)
				.build();

		Certificate saved = certificateRepository.save(certificate);
		log.info("Certificate generated: certificateNumber={}, userId={}, sessionId={}",
				saved.getCertificateNumber(),
				saved.getCertification().getUser().getUserId(),
				sessionId);
		return toResponse(saved);
	}

	@Override
	@Transactional(readOnly = true)
	public List<CertificateResponse> getMyCertificates(String email) {
		return certificateRepository.findByCertificationUserEmailIgnoreCaseOrderByIssueDateDesc(email).stream()
				.map(this::toResponse)
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public CertificateResponse getMyCertificate(String email, String certificateNumber) {
		Certificate certificate = certificateRepository
				.findByCertificateNumberIgnoreCaseAndCertificationUserEmailIgnoreCase(certificateNumber, email)
				.orElseThrow(() -> new ResourceNotFoundException("Certificate not found"));
		return toResponse(certificate);
	}

	@Override
	@Transactional(readOnly = true)
	@Cacheable(cacheNames = "certificateVerification", key = "#certificateNumber")
	public CertificateVerificationResponse verify(String certificateNumber) {
		Certificate certificate = certificateRepository.findByCertificateNumberIgnoreCase(certificateNumber)
				.orElse(null);

		if (certificate == null) {
			return new CertificateVerificationResponse(
					certificateNumber,
					CertificateVerificationStatus.INVALID,
					null,
					null,
					null,
					null,
					null,
					null,
					"Certificate number is invalid");
		}

		CertificateVerificationStatus status = certificate.getExpiryDate().isBefore(LocalDate.now())
				? CertificateVerificationStatus.EXPIRED
				: CertificateVerificationStatus.VALID;

		return new CertificateVerificationResponse(
				certificate.getCertificateNumber(),
				status,
				candidateName(certificate.getExamAttempt().getExamSession()),
				certificate.getCertification().getUser().getUserId(),
				certificate.getCertification().getCertificationLevel(),
				certificate.getIssueDate(),
				certificate.getExpiryDate(),
				certificate.getVerificationUrl(),
				status == CertificateVerificationStatus.VALID ? "Certificate is valid" : "Certificate is expired");
	}

	@Override
	@Transactional(readOnly = true)
	public CertificateFileContent downloadMyCertificate(String email, String certificateNumber) {
		Certificate certificate = certificateRepository
				.findByCertificateNumberIgnoreCaseAndCertificationUserEmailIgnoreCase(certificateNumber, email)
				.orElseThrow(() -> new ResourceNotFoundException("Certificate not found"));
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.CERTIFICATE_DOWNLOAD)
				.outcome(AuditOutcome.SUCCESS)
				.actorEmail(email)
				.actorUserId(certificate.getCertification().getUser().getUserId())
				.targetUserId(certificate.getCertification().getUser().getUserId())
				.targetType("CERTIFICATE")
				.targetId(certificateNumber)
				.build());
		return loadCertificateFile(certificate);
	}

	@Override
	@Transactional(readOnly = true)
	public CertificateFileContent downloadCertificateForAdmin(String certificateNumber) {
		Certificate certificate = certificateRepository.findByCertificateNumberIgnoreCase(certificateNumber)
				.orElseThrow(() -> new ResourceNotFoundException("Certificate not found"));
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.CERTIFICATE_DOWNLOAD)
				.outcome(AuditOutcome.SUCCESS)
				.targetUserId(certificate.getCertification().getUser().getUserId())
				.targetType("CERTIFICATE")
				.targetId(certificateNumber)
				.description("Downloaded via admin console")
				.build());
		return loadCertificateFile(certificate);
	}

	/**
	 * Streams the stored PDF, re-rendering it first if the file cannot be served
	 * as it stands.
	 *
	 * <p>The certificate row in the database is the record of issue; the file on
	 * disk is only a cache of it. A wiped volume, a restored backup or a moved
	 * storage directory used to turn every download into a 404 that had to be
	 * cleared by hand, so an unusable file is rebuilt from the persisted record
	 * instead. Every field still comes from the database, so a regenerated PDF
	 * carries exactly the same award as the one it replaces.
	 */
	private CertificateFileContent loadCertificateFile(Certificate certificate) {
		try {
			Path path = Paths.get(certificateStorageDirectory).toAbsolutePath().normalize()
					.resolve(certificate.getCertificateUrl()).normalize();
			Resource resource = new UrlResource(path.toUri());

			if (isStale(path)) {
				log.warn("Stored certificate file unusable or outdated, regenerating: certificateNumber={}, path={}",
						certificate.getCertificateNumber(), path);
				renderAndStore(certificate);
				resource = new UrlResource(path.toUri());
				if (!resource.exists()) {
					throw new ResourceNotFoundException("Certificate file not found");
				}
			}

			return new CertificateFileContent(resource, "application/pdf", certificate.getCertificateNumber() + ".pdf");
		} catch (IOException ex) {
			throw new BusinessException("Failed to load certificate file", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Whether the stored file has to be rendered again before it can be served.
	 *
	 * <p>Existence alone is not enough to trust a cached file. An interrupted
	 * write leaves a zero-byte or truncated PDF that opens to nothing, and a file
	 * written before an artwork change still carries the previous design — both
	 * download without error, which is worse than failing outright. Anything that
	 * cannot be opened and matched against the current
	 * {@link CertificatePdfGeneratorService#DESIGN_VERSION} is treated as stale
	 * and rebuilt, since re-rendering costs a few milliseconds and every field
	 * comes from the database anyway.
	 */
	private boolean isStale(Path path) {
		if (!Files.exists(path)) {
			return true;
		}
		try {
			if (Files.size(path) == 0) {
				return true;
			}
			try (PDDocument document = PDDocument.load(path.toFile())) {
				String storedVersion = document.getDocumentInformation()
						.getCustomMetadataValue(CertificatePdfGeneratorService.DESIGN_VERSION_KEY);
				return !CertificatePdfGeneratorService.DESIGN_VERSION.equals(storedVersion);
			}
		} catch (IOException | RuntimeException ex) {
			log.warn("Stored certificate file could not be inspected, treating as stale: path={}, reason={}",
					path, ex.getMessage());
			return true;
		}
	}

	/** Renders a certificate from its persisted record and writes it to storage. */
	private void renderAndStore(Certificate certificate) {
		byte[] qrCodePng = buildQrCodePng(certificate.getVerificationUrl());
		byte[] pdfBytes = certificatePdfGeneratorService.generateCertificatePdf(
				new CertificatePdfGeneratorService.CertificatePdfData(
						certificate.getCertificateNumber(),
						candidateName(certificate.getExamAttempt().getExamSession()),
						certificate.getCertification().getUser().getUserId(),
						certificate.getCertification().getCertificationLevel(),
						certificate.getIssueDate(),
						certificate.getExpiryDate(),
						certificate.getVerificationUrl(),
						qrCodePng));
		storePdf(certificate.getCertificateNumber(), pdfBytes);
	}

	private Certification getOrCreateCertification(ExamAttempt attempt) {
		ExamSession session = attempt.getExamSession();
		Long userId = session.getUser().getId();
		var level = session.getExam().getCertificationLevel();
		User lockedUser = userRepository.findByIdForUpdate(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));

		Optional<Certification> activeCertification = certificationRepository
				.findFirstByUserIdAndCertificationLevelAndCertificationStatusOrderByExpiryDateDesc(
						userId,
						level,
						CertificationStatus.ACTIVE);
		if (activeCertification.isPresent()) {
			return activeCertification.get();
		}

		return certificationRepository.save(Certification.builder()
				.user(lockedUser)
				.certificationLevel(level)
				.certificationStatus(CertificationStatus.ACTIVE)
				.issueDate(LocalDate.now())
				.expiryDate(LocalDate.now().plusYears(1))
				.build());
	}

	private String generateCertificateNumber(ExamAttempt attempt) {
		return ("CERT-" + LocalDate.now().getYear() + "-"
				+ attempt.getExamSession().getExam().getCertificationLevel().name() + "-"
				+ UUID.randomUUID().toString().substring(0, 8))
				.toUpperCase(Locale.ROOT);
	}

	private String buildVerificationUrl(String certificateNumber) {
		String normalized = verificationBaseUrl.endsWith("/")
				? verificationBaseUrl.substring(0, verificationBaseUrl.length() - 1)
				: verificationBaseUrl;
		return normalized + "/" + certificateNumber;
	}

	private byte[] buildQrCodePng(String verificationUrl) {
		try {
			QRCodeWriter qrCodeWriter = new QRCodeWriter();
			BitMatrix bitMatrix = qrCodeWriter.encode(
					verificationUrl,
					BarcodeFormat.QR_CODE,
					300,
					300,
					Map.of(EncodeHintType.MARGIN, 1));

			BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);
			java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
			ImageIO.write(image, "PNG", output);
			return output.toByteArray();
		} catch (WriterException | IOException ex) {
			throw new BusinessException("Failed to generate QR code", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private String storePdf(String certificateNumber, byte[] pdfBytes) {
		try {
			Path baseDirectory = Paths.get(certificateStorageDirectory).toAbsolutePath().normalize();
			Files.createDirectories(baseDirectory);

			String fileName = certificateNumber + ".pdf";
			Path filePath = baseDirectory.resolve(fileName).normalize();
			Files.write(filePath, pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			return fileName;
		} catch (IOException ex) {
			throw new BusinessException("Failed to store certificate PDF", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private CertificateResponse toResponse(Certificate certificate) {
		CertificateTemplate template = CertificateTemplate
				.forLevel(certificate.getCertification().getCertificationLevel());
		return new CertificateResponse(
				certificate.getCertificateNumber(),
				candidateName(certificate.getExamAttempt().getExamSession()),
				certificate.getCertification().getUser().getUserId(),
				certificate.getCertification().getCertificationLevel(),
				certificate.getIssueDate(),
				certificate.getExpiryDate(),
				certificate.getVerificationUrl(),
				"/api/certificates/" + certificate.getCertificateNumber() + "/download",
				template.awardTitle(),
				template.eyebrow(),
				template.tierLine(),
				template.citationText(),
				template.competencies(),
				template.levelIndex(),
				CertificateTemplate.TOTAL_LEVELS);
	}

	private String candidateName(ExamSession session) {
		return session.getUser().getFirstName() + " " + session.getUser().getLastName();
	}

	@Override
	@Transactional
	public Map<String, Object> generateMissingCertificates(String email) {
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));

		List<ExamAttempt> passedAttempts = examAttemptRepository
				.findByExamSessionUserAndResultStatusOrderBySubmittedAtDesc(user, ResultStatus.PASS);

		List<Map<String, Object>> generated = new ArrayList<>();
		for (ExamAttempt attempt : passedAttempts) {
			if (certificateRepository.findByExamAttempt(attempt).isPresent()) {
				continue;
			}

			try {
				CertificateResponse response = generateForSession(email, attempt.getExamSession().getId());
				Map<String, Object> info = new LinkedHashMap<>();
				info.put("sessionId", attempt.getExamSession().getId());
				info.put("certificateNumber", response.certificateNumber());
				info.put("status", "generated");
				generated.add(info);
				log.info("Auto-generated missing certificate for user={}, sessionId={}",
						email, attempt.getExamSession().getId());
			} catch (Exception ex) {
				Map<String, Object> error = new LinkedHashMap<>();
				error.put("sessionId", attempt.getExamSession().getId());
				error.put("status", "failed");
				error.put("error", ex.getMessage());
				generated.add(error);
				log.warn("Failed to generate certificate for user={}, sessionId={}: {}",
						email, attempt.getExamSession().getId(), ex.getMessage());
			}
		}

		long generatedCount = generated.stream().filter(m -> "generated".equals(m.get("status"))).count();
		long failedCount = generated.stream().filter(m -> "failed".equals(m.get("status"))).count();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("userId", user.getUserId());
		result.put("email", user.getEmail());
		result.put("generatedCount", generatedCount);
		result.put("failedCount", failedCount);
		result.put("details", generated);

		// Self-service, not an admin action: this endpoint takes no role check and
		// only ever touches the caller's own certificates (see the userRepository
		// lookup above), so the actor and target are the same person.
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.CERTIFICATE_MAINTENANCE)
				.outcome(failedCount == 0 ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
				.actorEmail(email)
				.actorUserId(user.getUserId())
				.targetUserId(user.getUserId())
				.targetType("CERTIFICATE")
				.description("Generated missing certificates: generated=" + generatedCount + ", failed=" + failedCount)
				.build());
		return result;
	}

	@Override
	@Transactional
	public Map<String, Object> regenerateMissingPdfFiles(String email) {
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));

		List<Certificate> certificates = certificateRepository
				.findByCertificationUserEmailIgnoreCaseOrderByIssueDateDesc(email);
		List<Map<String, Object>> regenerated = new ArrayList<>();

		for (Certificate cert : certificates) {
			try {
				Path certPath = Paths.get(certificateStorageDirectory).toAbsolutePath().normalize()
						.resolve(cert.getCertificateUrl()).normalize();
				// Files already carrying the current artwork are left alone; anything
				// missing, damaged or rendered by an earlier design is rewritten.
				if (!isStale(certPath)) {
					continue;
				}

				renderAndStore(cert);
				Map<String, Object> info = new LinkedHashMap<>();
				info.put("certificateNumber", cert.getCertificateNumber());
				info.put("status", "regenerated");
				regenerated.add(info);
				log.info("Regenerated missing PDF for certificate={}, user={}", cert.getCertificateNumber(), email);
			} catch (Exception ex) {
				Map<String, Object> error = new LinkedHashMap<>();
				error.put("certificateNumber", cert.getCertificateNumber());
				error.put("status", "failed");
				error.put("error", ex.getMessage());
				regenerated.add(error);
				log.warn("Failed to regenerate PDF for certificate={}, user={}: {}",
						cert.getCertificateNumber(), email, ex.getMessage());
			}
		}

		long regeneratedCount = regenerated.stream().filter(m -> "regenerated".equals(m.get("status"))).count();
		long failedCount = regenerated.stream().filter(m -> "failed".equals(m.get("status"))).count();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("userId", user.getUserId());
		result.put("email", user.getEmail());
		result.put("regeneratedCount", regeneratedCount);
		result.put("failedCount", failedCount);
		result.put("details", regenerated);

		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.CERTIFICATE_MAINTENANCE)
				.outcome(failedCount == 0 ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
				.actorEmail(email)
				.actorUserId(user.getUserId())
				.targetUserId(user.getUserId())
				.targetType("CERTIFICATE")
				.description("Regenerated certificate PDFs: regenerated=" + regeneratedCount + ", failed=" + failedCount)
				.build());
		return result;
	}

	@Override
	@Transactional(readOnly = true)
	public Object diagnoseCertificatesForUser(String email) {
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));

		List<Certification> certifications = certificationRepository.findByUserOrderByExpiryDateDesc(user);
		List<Map<String, Object>> diagnostics = new ArrayList<>();

		for (Certification cert : certifications) {
			Map<String, Object> certInfo = new LinkedHashMap<>();
			certInfo.put("certificationId", cert.getId());
			certInfo.put("certificationLevel", cert.getCertificationLevel());
			certInfo.put("certificationStatus", cert.getCertificationStatus());
			certInfo.put("issueDate", cert.getIssueDate());
			certInfo.put("expiryDate", cert.getExpiryDate());

			Optional<Certificate> certificateRecord = certificateRepository
					.findByCertificationAndExamAttemptNotNull(cert);
			if (certificateRecord.isPresent()) {
				Certificate certificate = certificateRecord.get();
				certInfo.put("certificateExists", true);
				certInfo.put("certificateNumber", certificate.getCertificateNumber());
				certInfo.put("certificateUrl", certificate.getCertificateUrl());
				certInfo.put("certificateIssueDateInCert", certificate.getIssueDate());
				certInfo.put("certificateExpiryDateInCert", certificate.getExpiryDate());

				if (certificate.getCertificateUrl() != null && !certificate.getCertificateUrl().isEmpty()) {
					try {
						Path path = Paths.get(certificateStorageDirectory).toAbsolutePath().normalize()
								.resolve(certificate.getCertificateUrl()).normalize();
						boolean fileExists = Files.exists(path);
						certInfo.put("pdfFileExists", fileExists);
						certInfo.put("pdfFilePath", path.toString());
					} catch (Exception ex) {
						certInfo.put("pdfFileExists", false);
						certInfo.put("pdfFileError", ex.getMessage());
					}
				} else {
					certInfo.put("pdfFileExists", false);
					certInfo.put("pdfFileError", "No certificate URL stored");
				}
			} else {
				certInfo.put("certificateExists", false);
				certInfo.put("reason", "No Certificate record found for this Certification");
			}

			diagnostics.add(certInfo);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("userId", user.getUserId());
		result.put("email", user.getEmail());
		result.put("totalCertifications", certifications.size());
		result.put("certifications", diagnostics);
		return result;
	}
}
