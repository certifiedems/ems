package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.ems.entity.Certificate;
import com.ems.entity.Certification;
import com.ems.entity.ExamAttempt;
import com.ems.entity.ExamSession;
import com.ems.entity.User;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.repository.CertificateRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.ExamAttemptRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;
import com.ems.service.CertificateFileContent;
import com.ems.service.CertificatePdfGeneratorService;
import com.ems.service.CertificateTemplate;

/**
 * Covers the download path's treatment of the file cached on disk.
 *
 * <p>Uses the real renderer rather than a mock, because what is being asserted
 * is precisely that the bytes on disk end up carrying the current artwork.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateServiceImplTest {

    private static final String EMAIL = "aaru@example.com";
    private static final String CERTIFICATE_NUMBER = "CERT-2026-L2-0A015D6F";

    @Mock
    private CertificateRepository certificateRepository;
    @Mock
    private CertificationRepository certificationRepository;
    @Mock
    private ExamAttemptRepository examAttemptRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditService auditService;

    @TempDir
    Path storageDirectory;

    private CertificateServiceImpl service;
    private Certificate certificate;

    @BeforeEach
    void setUp() {
        service = new CertificateServiceImpl(
                certificateRepository,
                certificationRepository,
                examAttemptRepository,
                userRepository,
                new PdfBoxCertificatePdfGeneratorService(),
                auditService);
        ReflectionTestUtils.setField(service, "certificateStorageDirectory", storageDirectory.toString());
        ReflectionTestUtils.setField(service, "verificationBaseUrl",
                "http://localhost:8080/api/certificates/verify");

        certificate = sampleCertificate();
        when(certificateRepository
                .findByCertificateNumberIgnoreCaseAndCertificationUserEmailIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.of(certificate));
    }

    @Test
    void rewritesAFileLeftBehindByAnEarlierDesign() throws IOException {
        // The previous renderer produced a plain text page with no design stamp.
        // Serving it unchanged is how a candidate ended up downloading the old
        // certificate long after the artwork had been replaced.
        Path stored = storageDirectory.resolve(CERTIFICATE_NUMBER + ".pdf");
        Files.write(stored, legacyPdf());

        CertificateFileContent content = service.downloadMyCertificate(EMAIL, CERTIFICATE_NUMBER);

        assertThat(designVersionOf(stored)).isEqualTo(CertificatePdfGeneratorService.DESIGN_VERSION);
        assertThat(textOf(stored).replaceAll("\\s+", ""))
                .contains(CertificateTemplate.L2_ADVANCED.titleLineOne().replaceAll("\\s+", ""))
                .contains("AaruVelan");
        assertThat(content.fileName()).isEqualTo(CERTIFICATE_NUMBER + ".pdf");
    }

    @Test
    void rewritesATruncatedFileRatherThanServingAnEmptyDownload() throws IOException {
        // An interrupted write leaves a zero-byte PDF. It exists, so the old
        // check passed it straight through and the browser saved a file that
        // would not open.
        Path stored = storageDirectory.resolve(CERTIFICATE_NUMBER + ".pdf");
        Files.write(stored, new byte[0]);

        service.downloadMyCertificate(EMAIL, CERTIFICATE_NUMBER);

        assertThat(Files.size(stored)).isPositive();
        assertThat(designVersionOf(stored)).isEqualTo(CertificatePdfGeneratorService.DESIGN_VERSION);
    }

    @Test
    void rendersAMissingFileFromThePersistedRecord() throws IOException {
        Path stored = storageDirectory.resolve(CERTIFICATE_NUMBER + ".pdf");

        service.downloadMyCertificate(EMAIL, CERTIFICATE_NUMBER);

        assertThat(stored).exists();
        assertThat(textOf(stored).replaceAll("\\s+", "")).contains("AaruVelan");
    }

    @Test
    void servesACurrentFileWithoutRewritingIt() throws IOException {
        // Re-rendering on every download would be wasted work, so a file already
        // carrying the current design must be streamed as it stands.
        service.downloadMyCertificate(EMAIL, CERTIFICATE_NUMBER);
        Path stored = storageDirectory.resolve(CERTIFICATE_NUMBER + ".pdf");
        byte[] first = Files.readAllBytes(stored);
        Files.setLastModifiedTime(stored, java.nio.file.attribute.FileTime.fromMillis(0));

        service.downloadMyCertificate(EMAIL, CERTIFICATE_NUMBER);

        assertThat(Files.readAllBytes(stored)).isEqualTo(first);
        assertThat(Files.getLastModifiedTime(stored).toMillis()).isZero();
    }

    private Certificate sampleCertificate() {
        User user = User.builder()
                .id(1L)
                .userId("Aaru@123")
                .firstName("Aaru")
                .lastName("Velan")
                .email(EMAIL)
                .build();

        ExamSession session = ExamSession.builder().id(10L).user(user).build();
        ExamAttempt attempt = ExamAttempt.builder().id(20L).examSession(session).build();
        Certification certification = Certification.builder()
                .id(30L)
                .user(user)
                .certificationLevel(CertificationLevel.L2)
                .certificationStatus(CertificationStatus.ACTIVE)
                .issueDate(LocalDate.of(2026, 8, 2))
                .expiryDate(LocalDate.of(2027, 8, 2))
                .build();

        return Certificate.builder()
                .id(40L)
                .certificateNumber(CERTIFICATE_NUMBER)
                .certification(certification)
                .examAttempt(attempt)
                .certificateUrl(CERTIFICATE_NUMBER + ".pdf")
                .verificationUrl("http://localhost:8080/api/certificates/verify/" + CERTIFICATE_NUMBER)
                .issueDate(LocalDate.of(2026, 8, 2))
                .expiryDate(LocalDate.of(2027, 8, 2))
                .build();
    }

    /** A valid PDF with no design stamp, standing in for a pre-redesign file. */
    private static byte[] legacyPdf() throws IOException {
        try (PDDocument document = new PDDocument();
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String designVersionOf(Path path) throws IOException {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            return document.getDocumentInformation()
                    .getCustomMetadataValue(CertificatePdfGeneratorService.DESIGN_VERSION_KEY);
        }
    }

    private static String textOf(Path path) throws IOException {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }
}
