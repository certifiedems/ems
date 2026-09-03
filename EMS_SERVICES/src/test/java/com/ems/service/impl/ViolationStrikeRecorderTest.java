package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import com.ems.dto.request.ViolationRequestDTO;
import com.ems.dto.response.ViolationLogResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamSession;
import com.ems.entity.ProctorEvidence;
import com.ems.entity.User;
import com.ems.entity.Violation;
import com.ems.enums.CertificationLevel;
import com.ems.enums.EvidenceStorageKind;
import com.ems.enums.ExamStatus;
import com.ems.enums.ProctoringAction;
import com.ems.enums.ViolationType;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.ProctorEvidenceRepository;
import com.ems.repository.ViolationRepository;
import com.ems.service.ProctorEvidenceStorageService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ViolationStrikeRecorderTest {

	private static final String CALLER_EMAIL = "candidate@example.com";
	private static final String STUDENT_ID = "EMS-0007";
	private static final Long EXAM_ID = 200L;

	@Mock
	private ExamSessionRepository examSessionRepository;

	@Mock
	private ViolationRepository violationRepository;

	@Mock
	private ProctorEvidenceRepository proctorEvidenceRepository;

	@Mock
	private CertificationApplicationRepository certificationApplicationRepository;

	@Mock
	private ProctorEvidenceStorageService proctorEvidenceStorageService;

	private ViolationStrikeRecorder recorder;

	private User user;
	private Exam exam;

	@BeforeEach
	void setUp() {
		user = User.builder().id(100L).userId(STUDENT_ID).email(CALLER_EMAIL).build();
		exam = Exam.builder().id(EXAM_ID).examCode("EX-L1").certificationLevel(CertificationLevel.L1).build();

		recorder = new ViolationStrikeRecorder(
				examSessionRepository,
				violationRepository,
				proctorEvidenceRepository,
				proctorEvidenceStorageService,
				new ExamInvalidationHandler(certificationApplicationRepository));

		when(violationRepository.save(any(Violation.class))).thenAnswer(invocation -> {
			Violation violation = invocation.getArgument(0);
			violation.setId(900L);
			return violation;
		});
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(proctorEvidenceRepository.save(any(ProctorEvidence.class))).thenAnswer(invocation -> {
			ProctorEvidence evidence = invocation.getArgument(0);
			evidence.setId(950L);
			return evidence;
		});
	}

	private ExamSession session(int violationCount, ExamStatus status) {
		ExamSession session = ExamSession.builder()
				.id(300L)
				.sessionToken(UUID.randomUUID())
				.user(user)
				.exam(exam)
				.sessionStatus(status)
				.violationCount(violationCount)
				.build();

		when(examSessionRepository.findTopByUserEmailIgnoreCaseAndExamIdAndSessionStatusOrderByIdDesc(
				CALLER_EMAIL, EXAM_ID, ExamStatus.IN_PROGRESS)).thenReturn(Optional.of(session));
		when(examSessionRepository.findTopByUserEmailIgnoreCaseAndExamIdOrderByIdDesc(CALLER_EMAIL, EXAM_ID))
				.thenReturn(Optional.of(session));
		when(examSessionRepository.findByIdForUpdate(300L)).thenReturn(Optional.of(session));
		return session;
	}

	private ViolationRequestDTO request(ViolationType type, String evidence) {
		return new ViolationRequestDTO(EXAM_ID, STUDENT_ID, type, evidence, "detected by worker", 0.87d);
	}

	@Test
	void record_firstStrikeWarnsAndKeepsSessionActive() {
		ExamSession session = session(0, ExamStatus.IN_PROGRESS);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, null));

		assertThat(response.strikeCount()).isEqualTo(1);
		assertThat(response.strikesRemaining()).isEqualTo(2);
		assertThat(response.isTerminated()).isFalse();
		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.WARNING);
		assertThat(session.getSessionStatus()).isEqualTo(ExamStatus.IN_PROGRESS);
		assertThat(session.getViolationCount()).isEqualTo(1);
		verify(certificationApplicationRepository, never()).save(any(CertificationApplication.class));
	}

	@Test
	void record_lowConfidenceDetectionIsFlaggedForReviewWithoutCountingAStrike() {
		ExamSession session = session(1, ExamStatus.IN_PROGRESS);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.EYES_OFF_SCREEN, null));

		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.FLAGGED_FOR_REVIEW);
		assertThat(response.isTerminated()).isFalse();
		assertThat(response.strikeCount()).as("gaze inference must not advance the counter").isEqualTo(1);
		assertThat(session.getViolationCount()).isEqualTo(1);
		assertThat(session.getSessionStatus()).isEqualTo(ExamStatus.IN_PROGRESS);
		// The detection itself is still durable — review needs a record to review.
		verify(violationRepository).save(any(Violation.class));
	}

	/**
	 * The scenario that motivated the review tier: a candidate one strike from
	 * termination must not be failed by a signal measured from two pixels of iris
	 * displacement.
	 */
	@Test
	void record_reviewOnlyDetectionCannotTerminateEvenAtTheStrikeLimit() {
		ExamSession session = session(ViolationStrikeRecorder.STRIKE_LIMIT - 1, ExamStatus.IN_PROGRESS);

		ViolationLogResponse response = recorder.record(
				CALLER_EMAIL, request(ViolationType.PROCTOR_SETUP_INVALID, null));

		assertThat(response.isTerminated()).isFalse();
		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.FLAGGED_FOR_REVIEW);
		assertThat(session.getSessionStatus()).isEqualTo(ExamStatus.IN_PROGRESS);
		verify(certificationApplicationRepository, never()).save(any(CertificationApplication.class));
	}

	/**
	 * The two halves of the sound-event policy, asserted together because the
	 * split between them is the whole design: the engine reports every sound it
	 * hears, and only the one it can actually name carries a consequence.
	 */
	@Test
	void record_voiceDetectionCountsAsAStrike() {
		ExamSession session = session(0, ExamStatus.IN_PROGRESS);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.VOICE_DETECTED, null));

		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.WARNING);
		assertThat(response.strikeCount()).isEqualTo(1);
		assertThat(session.getViolationCount()).isEqualTo(1);
	}

	/** A cough. Recorded so the room stays auditable, but it costs nothing. */
	@Test
	void record_firstUnidentifiedSoundsAreForgiven() {
		ExamSession session = session(1, ExamStatus.IN_PROGRESS);
		soundsAlreadyHeard(0);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.SOUND_DETECTED, null));

		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.LOGGED);
		assertThat(response.strikeCount())
				.as("a forgiven sound must not advance the counter")
				.isEqualTo(1);
		assertThat(session.getViolationCount()).isEqualTo(1);
		// Still durable: "what did the room sound like" is unanswerable otherwise.
		verify(violationRepository).save(any(Violation.class));
	}

	/** The candidate is told the next one bites, not just that this one did not. */
	@Test
	void record_forgivenSoundSaysTheNextOneWillCount() {
		session(0, ExamStatus.IN_PROGRESS);
		soundsAlreadyHeard(ViolationStrikeRecorder.UNIDENTIFIED_SOUND_GRACE - 1);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.SOUND_DETECTED, null));

		assertThat(response.policyMessage()).contains("the next one will count as a strike");
	}

	/** Past the grace, a repeating noise is a pattern and counts like anything else. */
	@Test
	void record_unidentifiedSoundCountsAsAStrikeOnceTheGraceIsSpent() {
		ExamSession session = session(1, ExamStatus.IN_PROGRESS);
		soundsAlreadyHeard(ViolationStrikeRecorder.UNIDENTIFIED_SOUND_GRACE);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.SOUND_DETECTED, null));

		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.WARNING);
		assertThat(response.strikeCount()).isEqualTo(2);
		assertThat(session.getViolationCount()).isEqualTo(2);
	}

	/**
	 * The platform has no invigilator queue, so a room that will not stop making
	 * noise has to be able to end an attempt on its own. Reaching here takes the
	 * grace plus three more sounds, each past the client's loudness floor and its
	 * 15-second cooldown — which is no longer a cough.
	 */
	@Test
	void record_unidentifiedSoundTerminatesAtTheStrikeLimit() {
		ExamSession session = session(ViolationStrikeRecorder.STRIKE_LIMIT - 1, ExamStatus.IN_PROGRESS);
		soundsAlreadyHeard(ViolationStrikeRecorder.UNIDENTIFIED_SOUND_GRACE);
		CertificationApplication application = liveApplication();

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.SOUND_DETECTED, null));

		assertThat(response.isTerminated()).isTrue();
		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.EXAM_TERMINATED);
		assertThat(session.getSessionStatus()).isEqualTo(ExamStatus.INVALIDATED);
		assertThat(application.getApplicationStatus())
				.isEqualTo(com.ems.enums.CertificationApplicationStatus.TERMINATED);
		verify(certificationApplicationRepository).save(application);
	}

	/** The grace is spent on sound alone — a phone in frame is never forgiven. */
	@Test
	void record_graceDoesNotExtendToOtherDetections() {
		session(0, ExamStatus.IN_PROGRESS);
		soundsAlreadyHeard(0);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, null));

		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.WARNING);
		assertThat(response.strikeCount()).isEqualTo(1);
	}

	/** Prior unidentified sounds on this session, as the locked read would see them. */
	private void soundsAlreadyHeard(long count) {
		when(violationRepository.countByExamSessionAndViolationType(
				any(ExamSession.class), org.mockito.ArgumentMatchers.eq(ViolationType.SOUND_DETECTED)))
				.thenReturn(count);
	}

	/** The candidate's live application, wired so the invalidation handler can find it. */
	private CertificationApplication liveApplication() {
		CertificationApplication application = CertificationApplication.builder()
				.id(400L)
				.user(user)
				.exam(exam)
				.applicationStatus(com.ems.enums.CertificationApplicationStatus.IN_PROGRESS)
				.remarks("Started")
				.build();
		when(certificationApplicationRepository
				.findTopByUserAndExamAndApplicationStatusOrderByAppliedOnDescIdDesc(
						user, exam, com.ems.enums.CertificationApplicationStatus.IN_PROGRESS))
				.thenReturn(Optional.of(application));
		return application;
	}

	@Test
	void record_reviewOnlyDetectionStillStoresItsEvidence() {
		session(0, ExamStatus.IN_PROGRESS);
		String base64 = Base64.getEncoder().encodeToString("fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8));

		ViolationLogResponse response = recorder.record(
				CALLER_EMAIL, request(ViolationType.EYES_OFF_SCREEN, base64));

		// Review is only possible if the frame that triggered it was kept.
		assertThat(response.evidenceStored()).isTrue();
		verify(proctorEvidenceRepository).save(any(ProctorEvidence.class));
	}

	@Test
	void record_thirdStrikeTerminatesAndFailsApplication() {
		ExamSession session = session(2, ExamStatus.IN_PROGRESS);
		CertificationApplication application = liveApplication();

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, null));

		assertThat(response.strikeCount()).isEqualTo(3);
		assertThat(response.strikesRemaining()).isZero();
		assertThat(response.isTerminated()).isTrue();
		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.EXAM_TERMINATED);
		assertThat(session.getSessionStatus()).isEqualTo(ExamStatus.INVALIDATED);
		assertThat(session.getSessionEndTime()).isNotNull();
		assertThat(application.getApplicationStatus())
				.isEqualTo(com.ems.enums.CertificationApplicationStatus.TERMINATED);
		verify(certificationApplicationRepository).save(application);
	}

	@Test
	void record_strikeCountIsDerivedFromTheLockedRowNotTheRequest() {
		// Simulates a racing detection: the row read under lock already shows 2 strikes,
		// so this violation must land as strike 3 rather than re-deriving from a stale read.
		session(2, ExamStatus.IN_PROGRESS);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.MULTIPLE_FACES, null));

		verify(examSessionRepository).findByIdForUpdate(300L);
		assertThat(response.strikeCount()).isEqualTo(3);
	}

	@Test
	void record_ignoresViolationsOnAnAlreadyInvalidatedSession() {
		session(3, ExamStatus.INVALIDATED);

		ViolationLogResponse response = recorder.record(CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, null));

		assertThat(response.isTerminated()).isTrue();
		assertThat(response.violationId()).isNull();
		assertThat(response.strikeCount()).isEqualTo(3);
		verify(violationRepository, never()).save(any(Violation.class));
	}

	@Test
	void record_rejectsMismatchedStudentId() {
		session(0, ExamStatus.IN_PROGRESS);
		ViolationRequestDTO forged = new ViolationRequestDTO(
				EXAM_ID, "EMS-9999", ViolationType.PHONE_DETECTED, null, "forged", null);

		assertThatThrownBy(() -> recorder.record(CALLER_EMAIL, forged))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("does not match the authenticated candidate");

		verify(violationRepository, never()).save(any(Violation.class));
	}

	@Test
	void record_failsWhenCallerHasNoSessionForTheExam() {
		when(examSessionRepository.findTopByUserEmailIgnoreCaseAndExamIdAndSessionStatusOrderByIdDesc(
				CALLER_EMAIL, EXAM_ID, ExamStatus.IN_PROGRESS)).thenReturn(Optional.empty());
		when(examSessionRepository.findTopByUserEmailIgnoreCaseAndExamIdOrderByIdDesc(CALLER_EMAIL, EXAM_ID))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> recorder.record(CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, null)))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void record_storesEvidenceStrippedOfDataUriPrefix() {
		session(0, ExamStatus.IN_PROGRESS);
		byte[] rawBytes = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
		String base64 = Base64.getEncoder().encodeToString(rawBytes);

		ViolationLogResponse response = recorder.record(
				CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, "data:image/jpeg;base64," + base64));

		ArgumentCaptor<ProctorEvidence> captor = ArgumentCaptor.forClass(ProctorEvidence.class);
		verify(proctorEvidenceRepository).save(captor.capture());
		ProctorEvidence saved = captor.getValue();

		assertThat(saved.getEvidencePayload()).isEqualTo(base64);
		assertThat(saved.getMediaType()).isEqualTo("image/jpeg");
		assertThat(saved.getPayloadBytes()).isEqualTo(rawBytes.length);
		assertThat(saved.getStorageKind()).isEqualTo(EvidenceStorageKind.INLINE_BASE64);
		assertThat(response.evidenceStored()).isTrue();
		assertThat(response.evidenceId()).isEqualTo(950L);
	}

	@Test
	void record_stillCountsTheStrikeWhenEvidenceIsMalformed() {
		session(0, ExamStatus.IN_PROGRESS);

		ViolationLogResponse response = recorder.record(
				CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, "!!!not-base64!!!"));

		assertThat(response.strikeCount()).isEqualTo(1);
		assertThat(response.evidenceStored()).isFalse();
		verify(proctorEvidenceRepository, never()).save(any(ProctorEvidence.class));
	}

	@Test
	void record_offloadsTheFrameToObjectStorageWhenTheBackendIsEnabled() {
		session(0, ExamStatus.IN_PROGRESS);
		byte[] rawBytes = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
		String base64 = Base64.getEncoder().encodeToString(rawBytes);

		when(proctorEvidenceStorageService.isObjectStorageEnabled()).thenReturn(true);
		when(proctorEvidenceStorageService.storeEvidence(
				any(byte[].class), any(String.class), any(Long.class), any(Long.class)))
				.thenReturn("sessions/300/900-abc.jpg");

		ViolationLogResponse response = recorder.record(
				CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, "data:image/jpeg;base64," + base64));

		ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
		verify(proctorEvidenceStorageService).storeEvidence(
				bytesCaptor.capture(), eq("image/jpeg"), eq(300L), eq(900L));
		assertThat(bytesCaptor.getValue()).isEqualTo(rawBytes);

		ArgumentCaptor<ProctorEvidence> captor = ArgumentCaptor.forClass(ProctorEvidence.class);
		verify(proctorEvidenceRepository).save(captor.capture());
		ProctorEvidence saved = captor.getValue();

		assertThat(saved.getStorageKind()).isEqualTo(EvidenceStorageKind.OBJECT_STORAGE);
		assertThat(saved.getObjectStorageKey()).isEqualTo("sessions/300/900-abc.jpg");
		// The whole point: the database row no longer carries the bytes.
		assertThat(saved.getEvidencePayload()).isNull();
		assertThat(saved.getPayloadBytes()).isEqualTo(rawBytes.length);
		assertThat(response.evidenceStored()).isTrue();
	}

	@Test
	void record_fallsBackToInlineStorageWhenTheUploadFails() {
		session(0, ExamStatus.IN_PROGRESS);
		byte[] rawBytes = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
		String base64 = Base64.getEncoder().encodeToString(rawBytes);

		when(proctorEvidenceStorageService.isObjectStorageEnabled()).thenReturn(true);
		when(proctorEvidenceStorageService.storeEvidence(
				any(byte[].class), any(String.class), any(Long.class), any(Long.class)))
				.thenThrow(new BusinessException("R2 unreachable", HttpStatus.INTERNAL_SERVER_ERROR));

		ViolationLogResponse response = recorder.record(
				CALLER_EMAIL, request(ViolationType.PHONE_DETECTED, "data:image/jpeg;base64," + base64));

		ArgumentCaptor<ProctorEvidence> captor = ArgumentCaptor.forClass(ProctorEvidence.class);
		verify(proctorEvidenceRepository).save(captor.capture());
		ProctorEvidence saved = captor.getValue();

		assertThat(saved.getStorageKind()).isEqualTo(EvidenceStorageKind.INLINE_BASE64);
		assertThat(saved.getEvidencePayload()).isEqualTo(base64);
		assertThat(saved.getObjectStorageKey()).isNull();
		// A bucket outage must not cost the candidate their strike record.
		assertThat(response.strikeCount()).isEqualTo(1);
		assertThat(response.evidenceStored()).isTrue();
	}
}
