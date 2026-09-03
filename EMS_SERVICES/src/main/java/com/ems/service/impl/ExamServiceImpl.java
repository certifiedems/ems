package com.ems.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.dto.request.ExamDurationUpdateRequest;
import com.ems.dto.request.ExamPassingMarksUpdateRequest;
import com.ems.dto.request.ExamScheduleRequest;
import com.ems.dto.request.ExamUpsertRequest;
import com.ems.dto.response.ExamResponse;
import com.ems.entity.Exam;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.QuestionSeverity;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.ExamRepository;
import com.ems.service.ExamService;
import com.ems.util.ExamQuestionBlueprint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class ExamServiceImpl implements ExamService {

	private final ExamRepository examRepository;

	@Override
	public ExamResponse create(ExamUpsertRequest request) {
		validateRequest(request);
		ExamQuestionBlueprint blueprint = resolveBlueprint(request);
		if (examRepository.existsByExamCodeIgnoreCase(request.examCode())) {
			throw new BusinessException("Exam code already exists", HttpStatus.CONFLICT);
		}

		Exam exam = Exam.builder()
				.examCode(request.examCode().trim().toUpperCase())
				.examName(request.examName().trim())
				.certificationLevel(request.certificationLevel())
				.durationMinutes(request.durationMinutes())
				.totalMarks(request.totalMarks())
				.passingPercentage(request.passingPercentage())
				.totalQuestions(blueprint.totalQuestions())
				.lowSeverityPercentage(blueprint.lowSeverityPercentage())
				.mediumSeverityPercentage(blueprint.mediumSeverityPercentage())
				.highSeverityPercentage(blueprint.highSeverityPercentage())
				.examStatus(ExamStatus.SCHEDULED)
				.published(false)
				.build();

		Exam savedExam = examRepository.save(exam);
		log.info("Exam created: code={}, id={}", savedExam.getExamCode(), savedExam.getId());
		return toResponse(savedExam);
	}

	@Override
	public ExamResponse update(Long examId, ExamUpsertRequest request) {
		validateRequest(request);
		ExamQuestionBlueprint blueprint = resolveBlueprint(request);

		Exam existingExam = findExam(examId);
		examRepository.findByExamCodeIgnoreCase(request.examCode())
				.filter(exam -> !exam.getId().equals(examId))
				.ifPresent(exam -> {
					throw new BusinessException("Exam code already exists", HttpStatus.CONFLICT);
				});

		existingExam.setExamCode(request.examCode().trim().toUpperCase());
		existingExam.setExamName(request.examName().trim());
		existingExam.setCertificationLevel(request.certificationLevel());
		existingExam.setDurationMinutes(request.durationMinutes());
		existingExam.setTotalMarks(request.totalMarks());
		existingExam.setPassingPercentage(request.passingPercentage());
		existingExam.setTotalQuestions(blueprint.totalQuestions());
		existingExam.setLowSeverityPercentage(blueprint.lowSeverityPercentage());
		existingExam.setMediumSeverityPercentage(blueprint.mediumSeverityPercentage());
		existingExam.setHighSeverityPercentage(blueprint.highSeverityPercentage());

		Exam savedExam = examRepository.save(existingExam);
		log.info("Exam updated: code={}, id={}", savedExam.getExamCode(), savedExam.getId());
		return toResponse(savedExam);
	}

	@Override
	public void delete(Long examId) {
		Exam exam = findExam(examId);
		examRepository.delete(exam);
		log.info("Exam deleted: code={}, id={}", exam.getExamCode(), exam.getId());
	}

	@Override
	public ExamResponse publish(Long examId) {
		Exam exam = findExam(examId);
		exam.setPublished(true);
		Exam savedExam = examRepository.save(exam);
		log.info("Exam published: code={}, id={}", savedExam.getExamCode(), savedExam.getId());
		return toResponse(savedExam);
	}

	@Override
	public ExamResponse schedule(Long examId, ExamScheduleRequest request) {
		Exam exam = findExam(examId);
		if (!request.scheduledEndTime().isAfter(request.scheduledStartTime())) {
			throw new BusinessException("Scheduled end time must be after scheduled start time");
		}

		exam.setScheduledStartTime(request.scheduledStartTime());
		exam.setScheduledEndTime(request.scheduledEndTime());
		exam.setExamStatus(ExamStatus.SCHEDULED);
		Exam savedExam = examRepository.save(exam);
		log.info("Exam scheduled: code={}, id={}", savedExam.getExamCode(), savedExam.getId());
		return toResponse(savedExam);
	}

	@Override
	public ExamResponse updateDuration(Long examId, ExamDurationUpdateRequest request) {
		Exam exam = findExam(examId);
		exam.setDurationMinutes(request.durationMinutes());
		Exam savedExam = examRepository.save(exam);
		return toResponse(savedExam);
	}

	@Override
	public ExamResponse updatePassingMarks(Long examId, ExamPassingMarksUpdateRequest request) {
		Exam exam = findExam(examId);
		exam.setPassingPercentage(request.passingPercentage());
		Exam savedExam = examRepository.save(exam);
		return toResponse(savedExam);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ExamResponse> search(String examCode, String examName, CertificationLevel certificationLevel,
			ExamStatus examStatus, Boolean published) {
		String examCodePattern = toLikePattern(examCode);
		String examNamePattern = toLikePattern(examName);

		return examRepository.search(examCodePattern, examNamePattern, certificationLevel, examStatus, published).stream()
				.map(this::toResponse)
				.toList();
	}

	private String toLikePattern(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
	}

	private Exam findExam(Long examId) {
		return examRepository.findById(examId)
				.orElseThrow(() -> new ResourceNotFoundException("Exam not found"));
	}

	private void validateRequest(ExamUpsertRequest request) {
		if (request.passingPercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
			throw new BusinessException("Passing percentage must not exceed 100");
		}
	}

	/**
	 * The blueprint the request is asking for, or the standard paper when it
	 * asks for nothing.
	 *
	 * <p>The four fields move together: a request that sets some of them and
	 * leaves the rest out is rejected rather than quietly mixed with defaults,
	 * because half a mix is not a mix — pairing a caller's 50% LOW with a
	 * default 40% MEDIUM would build a paper nobody asked for and still pass
	 * every field-level check.</p>
	 */
	private ExamQuestionBlueprint resolveBlueprint(ExamUpsertRequest request) {
		boolean anySet = request.totalQuestions() != null
				|| request.lowSeverityPercentage() != null
				|| request.mediumSeverityPercentage() != null
				|| request.highSeverityPercentage() != null;
		if (!anySet) {
			return ExamQuestionBlueprint.defaults();
		}

		boolean allSet = request.totalQuestions() != null
				&& request.lowSeverityPercentage() != null
				&& request.mediumSeverityPercentage() != null
				&& request.highSeverityPercentage() != null;
		if (!allSet) {
			throw new BusinessException(
					"Question blueprint is incomplete: set total questions and all three severity percentages together");
		}

		/*
		 * Rounded to the two decimals the column holds before anything is
		 * derived from them. A caller sending 33.333 would otherwise be shown a
		 * split worked out from 33.333 and get one worked out from the stored
		 * 33.33 the next time the exam was read back.
		 */
		ExamQuestionBlueprint blueprint = new ExamQuestionBlueprint(
				request.totalQuestions(),
				request.lowSeverityPercentage().setScale(2, RoundingMode.HALF_UP),
				request.mediumSeverityPercentage().setScale(2, RoundingMode.HALF_UP),
				request.highSeverityPercentage().setScale(2, RoundingMode.HALF_UP));

		BigDecimal total = blueprint.percentageTotal();
		if (total.compareTo(ExamQuestionBlueprint.REQUIRED_PERCENTAGE_TOTAL) != 0) {
			throw new BusinessException(String.format(
					"Severity percentages must add up to 100 (low + medium + high = %s)",
					total.stripTrailingZeros().toPlainString()));
		}

		return blueprint;
	}

	private ExamResponse toResponse(Exam exam) {
		Instant createdAt = exam.getCreatedDate() == null ? null : exam.getCreatedDate().toInstant(ZoneOffset.UTC);
		Instant updatedAt = exam.getUpdatedDate() == null ? null : exam.getUpdatedDate().toInstant(ZoneOffset.UTC);

		ExamQuestionBlueprint blueprint = ExamQuestionBlueprint.of(exam);
		Map<QuestionSeverity, Integer> questionCounts = blueprint.questionCounts();

		return new ExamResponse(
				exam.getId(),
				exam.getExamCode(),
				exam.getExamName(),
				exam.getCertificationLevel(),
				exam.getDurationMinutes(),
				exam.getTotalMarks(),
				exam.getPassingPercentage(),
				blueprint.totalQuestions(),
				blueprint.lowSeverityPercentage(),
				blueprint.mediumSeverityPercentage(),
				blueprint.highSeverityPercentage(),
				questionCounts.get(QuestionSeverity.LOW),
				questionCounts.get(QuestionSeverity.MEDIUM),
				questionCounts.get(QuestionSeverity.HIGH),
				exam.getExamStatus(),
				exam.isPublished(),
				exam.getScheduledStartTime(),
				exam.getScheduledEndTime(),
				createdAt,
				updatedAt);
	}
}
