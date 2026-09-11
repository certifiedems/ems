package com.ems.service.impl;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.QuestionUpsertRequest;
import com.ems.dto.response.BulkQuestionDeleteResponse;
import com.ems.dto.response.BulkQuestionUploadResponse;
import com.ems.dto.response.QuestionResponse;
import com.ems.entity.Question;
import com.ems.enums.CertificationLevel;
import com.ems.enums.QuestionCategory;
import com.ems.enums.QuestionSeverity;
import com.ems.enums.QuestionType;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.QuestionRepository;
import com.ems.service.AuditService;
import com.ems.service.QuestionService;
import com.ems.util.QuestionBulkFileReader;
import com.ems.util.QuestionBulkFileReader.Column;
import com.ems.util.QuestionBulkFileReader.QuestionRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class QuestionServiceImpl implements QuestionService {

	/**
	 * Sequence then severity marker, with an optional level prefix: Q001L or
	 * L2Q001L. Without the prefix, the level comes from the Level column.
	 */
	private static final Pattern SEQUENCE_QUESTION_CODE_PATTERN = Pattern.compile("^(L[123])?Q\\d{3,}([LMH])$");
	/** Older format with the level built in, e.g. L1L001; still accepted for existing questions. */
	private static final Pattern LEVEL_QUESTION_CODE_PATTERN = Pattern.compile("^(L[123])([LMH])\\d{3,}$");
	private static final String QUESTION_CODE_FORMAT_MESSAGE =
			"Question code must match format like Q001L or L2Q001L (or L1L001)";
	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};

	private final QuestionRepository questionRepository;
	private final ObjectMapper objectMapper;
	private final AuditService auditService;
	private final TransactionTemplate transactionTemplate;

	@Override
	@CacheEvict(cacheNames = { "questionById", "questionSearch", "reports" }, allEntries = true)
	public QuestionResponse create(QuestionUpsertRequest request) {
		validateQuestionRequest(request);
		if (questionRepository.existsByQuestionCodeIgnoreCase(request.questionCode())) {
			throw new BusinessException("Question code already exists", HttpStatus.CONFLICT);
		}

		Question savedQuestion = questionRepository.save(toEntity(request, null));
		log.info("Question created: code={}", savedQuestion.getQuestionCode());
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetType("QUESTION")
				.targetId(savedQuestion.getQuestionCode())
				.description("Created question " + savedQuestion.getQuestionCode())
				.build());
		return toResponse(savedQuestion);
	}

	@Override
	@Transactional(readOnly = true)
	@Cacheable(cacheNames = "questionById", key = "#questionId")
	public QuestionResponse getById(Long questionId) {
		return toResponse(questionRepository.findById(questionId)
				.orElseThrow(() -> new ResourceNotFoundException("Question not found")));
	}

	@Override
	@CacheEvict(cacheNames = { "questionById", "questionSearch", "reports" }, allEntries = true)
	public QuestionResponse update(Long questionId, QuestionUpsertRequest request) {
		validateQuestionRequest(request);

		Question existingQuestion = questionRepository.findById(questionId)
				.orElseThrow(() -> new ResourceNotFoundException("Question not found"));

		questionRepository.findByQuestionCodeIgnoreCase(request.questionCode())
				.filter(question -> !question.getId().equals(questionId))
				.ifPresent(question -> {
					throw new BusinessException("Question code already exists", HttpStatus.CONFLICT);
				});

		Question savedQuestion = questionRepository.save(toEntity(request, existingQuestion));
		log.info("Question updated: id={}, code={}", savedQuestion.getId(), savedQuestion.getQuestionCode());
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetType("QUESTION")
				.targetId(savedQuestion.getQuestionCode())
				.description("Updated question " + savedQuestion.getQuestionCode())
				.build());
		return toResponse(savedQuestion);
	}

	@Override
	@CacheEvict(cacheNames = { "questionById", "questionSearch", "reports" }, allEntries = true)
	public void delete(Long questionId) {
		Question existingQuestion = questionRepository.findById(questionId)
				.orElseThrow(() -> new ResourceNotFoundException("Question not found"));
		questionRepository.delete(existingQuestion);
		log.info("Question deleted: id={}, code={}", existingQuestion.getId(), existingQuestion.getQuestionCode());
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetType("QUESTION")
				.targetId(existingQuestion.getQuestionCode())
				.description("Deleted question " + existingQuestion.getQuestionCode())
				.build());
	}

	@Override
	@CacheEvict(cacheNames = { "questionById", "questionSearch", "reports" }, allEntries = true)
	public BulkQuestionDeleteResponse bulkDelete(List<Long> questionIds) {
		List<String> errors = new ArrayList<>();
		int deletedCount = 0;

		for (Long questionId : questionIds) {
			Question question = questionRepository.findById(questionId).orElse(null);
			if (question == null) {
				errors.add("Question id " + questionId + " not found");
				continue;
			}
			questionRepository.delete(question);
			deletedCount++;
		}

		log.info("Bulk question delete: requested={}, deleted={}, failed={}",
				questionIds.size(), deletedCount, errors.size());
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetType("QUESTION")
				.description("Bulk deleted questions: requested=" + questionIds.size()
						+ ", deleted=" + deletedCount + ", failed=" + errors.size())
				.build());
		return new BulkQuestionDeleteResponse(questionIds.size(), deletedCount, errors.size(), errors);
	}

	@Override
	@Transactional(readOnly = true)
	@Cacheable(cacheNames = "questionSearch", key = "T(java.util.Objects).hash(#questionCode, #certificationLevel, #severity, #active, #searchText)")
	public List<QuestionResponse> search(
			String questionCode,
			CertificationLevel certificationLevel,
			QuestionSeverity severity,
			Boolean active,
			String searchText) {
		String questionCodePattern = toLikePattern(questionCode);
		String searchTextPattern = toLikePattern(searchText);

		return questionRepository.search(questionCodePattern, certificationLevel, severity, active, searchTextPattern).stream()
				.map(this::toResponse)
				.toList();
	}

	private String toLikePattern(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
	}

	/**
	 * Runs outside a transaction and commits each row in its own. In one shared
	 * transaction, a single insert the database refused marked the whole batch
	 * rollback-only: every row was lost and the commit failed the request with a
	 * bare 500 instead of reporting the row.
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@CacheEvict(cacheNames = { "questionById", "questionSearch", "reports" }, allEntries = true)
	public BulkQuestionUploadResponse bulkUpload(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessException("Bulk upload file is required", HttpStatus.BAD_REQUEST);
		}

		List<QuestionRow> rows;
		try {
			rows = QuestionBulkFileReader.read(file);
		} catch (IOException ex) {
			throw new BusinessException("Failed to read bulk upload file", HttpStatus.BAD_REQUEST);
		}
		if (rows.isEmpty()) {
			throw new BusinessException("Bulk upload file does not contain any questions", HttpStatus.BAD_REQUEST);
		}

		int createdRows = 0;
		int updatedRows = 0;
		List<String> errors = new ArrayList<>();
		Map<String, String> firstRowByQuestionCode = new HashMap<>();

		for (QuestionRow row : rows) {
			String questionCode = row.get(Column.QUES_ID).toUpperCase(Locale.ROOT);
			String rowReference = questionCode.isEmpty() ? row.label() : row.label() + " (" + questionCode + ")";
			try {
				String firstRow = questionCode.isEmpty()
						? null
						: firstRowByQuestionCode.putIfAbsent(questionCode, row.label());
				if (firstRow != null) {
					throw new BusinessException("quesID is repeated in this file (first used on " + firstRow + ")");
				}

				QuestionUpsertRequest request = parseBulkRow(row);
				if (Boolean.TRUE.equals(transactionTemplate.execute(status -> importRow(request)))) {
					createdRows++;
				} else {
					updatedRows++;
				}
			} catch (BusinessException ex) {
				errors.add(rowReference + ": " + ex.getMessage());
			} catch (RuntimeException ex) {
				log.warn("Bulk upload row failed: {}", rowReference, ex);
				errors.add(rowReference + ": " + describeFailure(ex));
			}
		}

		// One summary row for the batch as a whole; each imported/updated question
		// also logged its own ADMIN_ACTION above via create()/update(), so a
		// specific bad row within a batch is still traceable on its own.
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(errors.isEmpty() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
				.targetType("QUESTION")
				.description("Bulk uploaded questions: totalRows=" + rows.size()
						+ ", created=" + createdRows + ", updated=" + updatedRows + ", failed=" + errors.size())
				.build());

		return new BulkQuestionUploadResponse(
				rows.size(), createdRows + updatedRows, createdRows, updatedRows, errors.size(), errors);
	}

	/** Creates or updates one uploaded question; returns true when it was created. */
	private boolean importRow(QuestionUpsertRequest request) {
		Question existingQuestion = questionRepository.findByQuestionCodeIgnoreCase(request.questionCode())
				.orElse(null);
		if (existingQuestion == null) {
			create(request);
			return true;
		}
		if (existingQuestion.getCertificationLevel() != request.certificationLevel()) {
			// quesID is unique across all levels, so updating here would quietly
			// move an existing question (and its exam history) to another level.
			throw new BusinessException("quesID already exists for level " + existingQuestion.getCertificationLevel()
					+ "; use a different quesID for this " + request.certificationLevel() + " question");
		}
		update(existingQuestion.getId(), request);
		return false;
	}

	/** The database's own reason (first line only), e.g. which constraint a row broke. */
	private String describeFailure(RuntimeException ex) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
		return cause.getMessage() == null
				? cause.getClass().getSimpleName()
				: cause.getMessage().lines().findFirst().orElse(cause.getClass().getSimpleName());
	}

	private QuestionUpsertRequest parseBulkRow(QuestionRow row) {
		String questionCode = requiredValue(row, Column.QUES_ID).toUpperCase(Locale.ROOT);
		List<String> options = List.of(
				requiredValue(row, Column.OPTION1),
				requiredValue(row, Column.OPTION2),
				requiredValue(row, Column.OPTION3),
				requiredValue(row, Column.OPTION4));

		// Several correct answers are separated by "|". Each is stored with its
		// option's own spelling, so "kwh" in the answer column saves as "kWh".
		List<String> correctOptions = Arrays.stream(requiredValue(row, Column.ANSWER).split("\\|"))
				.map(String::strip)
				.filter(answer -> !answer.isEmpty())
				.map(answer -> options.stream()
						.filter(option -> option.equalsIgnoreCase(answer))
						.findFirst()
						.orElse(answer))
				.distinct()
				.toList();

		QuestionType questionType = correctOptions.size() > 1
				? QuestionType.MULTIPLE_CHOICE
				: QuestionType.SINGLE_CHOICE;

		QuestionCategory questionCategory = row.get(Column.CATEGORY).isEmpty()
				? QuestionCategory.GENERAL
				: parseEnum(QuestionCategory.class, row.get(Column.CATEGORY), Column.CATEGORY);

		return new QuestionUpsertRequest(
				questionCode,
				resolveLevel(row.get(Column.LEVEL), questionCode),
				questionCategory,
				questionType,
				requiredValue(row, Column.QUESTION),
				options,
				correctOptions,
				parseEnum(QuestionSeverity.class, requiredValue(row, Column.SEVERITY), Column.SEVERITY),
				parseMarks(row.get(Column.MARKS)),
				true);
	}

	private String requiredValue(QuestionRow row, Column column) {
		String value = row.get(column);
		if (value.isEmpty()) {
			throw new BusinessException(column.headerName() + " is required");
		}
		return value;
	}

	private CertificationLevel resolveLevel(String level, String questionCode) {
		if (level.isEmpty()) {
			CertificationLevel codeLevel = levelInCode(questionCode);
			if (codeLevel != null) {
				return codeLevel;
			}
			throw new BusinessException(SEQUENCE_QUESTION_CODE_PATTERN.matcher(questionCode).matches()
					? "Level is required (L1, L2 or L3)"
					: QUESTION_CODE_FORMAT_MESSAGE);
		}

		// Accepts "L1", "1" and "Level 1".
		String normalized = level.toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z0-9]", "")
				.replaceFirst("^(LEVEL|L)?", "L");
		try {
			return CertificationLevel.valueOf(normalized);
		} catch (IllegalArgumentException ex) {
			throw new BusinessException("Invalid Level '" + level + "'; expected L1, L2 or L3");
		}
	}

	/** The level a question code carries (L2Q001L, L1L001), or null for a code like Q001L that has none. */
	private CertificationLevel levelInCode(String questionCode) {
		Matcher sequenceCode = SEQUENCE_QUESTION_CODE_PATTERN.matcher(questionCode);
		if (sequenceCode.matches()) {
			return sequenceCode.group(1) == null ? null : CertificationLevel.valueOf(sequenceCode.group(1));
		}
		Matcher levelCode = LEVEL_QUESTION_CODE_PATTERN.matcher(questionCode);
		return levelCode.matches() ? CertificationLevel.valueOf(levelCode.group(1)) : null;
	}

	private <E extends Enum<E>> E parseEnum(Class<E> enumType, String value, Column column) {
		String normalized = value.toUpperCase(Locale.ROOT).replace(' ', '_');
		return Arrays.stream(enumType.getEnumConstants())
				.filter(constant -> constant.name().equals(normalized))
				.findFirst()
				.orElseThrow(() -> new BusinessException("Invalid " + column.headerName() + " '" + value + "'; expected "
						+ Arrays.stream(enumType.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "))));
	}

	private BigDecimal parseMarks(String marks) {
		if (marks.isEmpty()) {
			return BigDecimal.ONE;
		}
		try {
			BigDecimal value = new BigDecimal(marks);
			if (value.signum() < 0) {
				throw new BusinessException("marks cannot be negative");
			}
			return value;
		} catch (NumberFormatException ex) {
			throw new BusinessException("Invalid marks '" + marks + "'; expected a number");
		}
	}

	private void validateQuestionRequest(QuestionUpsertRequest request) {
		String questionCode = request.questionCode().trim().toUpperCase(Locale.ROOT);
		Matcher sequenceCode = SEQUENCE_QUESTION_CODE_PATTERN.matcher(questionCode);
		Matcher levelCode = LEVEL_QUESTION_CODE_PATTERN.matcher(questionCode);

		String severityMarker;
		if (sequenceCode.matches()) {
			severityMarker = sequenceCode.group(2);
		} else if (levelCode.matches()) {
			severityMarker = levelCode.group(2);
		} else {
			throw new BusinessException(QUESTION_CODE_FORMAT_MESSAGE);
		}

		CertificationLevel codeLevel = levelInCode(questionCode);
		if (codeLevel != null && codeLevel != request.certificationLevel()) {
			throw new BusinessException("Question code level (" + codeLevel + ") does not match Level "
					+ request.certificationLevel());
		}

		if (!severityMarker.equals(request.severity().code())) {
			throw new BusinessException("Question code severity marker (" + severityMarker
					+ ") does not match severity " + request.severity());
		}

		if (request.options().size() != 4) {
			throw new BusinessException("Exactly 4 options are required");
		}

		if (request.questionType() == QuestionType.SINGLE_CHOICE && request.correctOptions().size() != 1) {
			throw new BusinessException("Single Choice questions must have exactly one correct option");
		}

		if (request.questionType() == QuestionType.MULTIPLE_CHOICE && request.correctOptions().size() < 2) {
			throw new BusinessException("Multiple Choice questions must have at least two correct options");
		}

		for (String correctOption : request.correctOptions()) {
			if (request.options().stream().noneMatch(option -> option.equalsIgnoreCase(correctOption))) {
				throw new BusinessException("Answer '" + correctOption + "' does not match any of the options");
			}
		}
	}

	private Question toEntity(QuestionUpsertRequest request, Question existingQuestion) {
		Question question = existingQuestion == null ? new Question() : existingQuestion;
		question.setQuestionCode(request.questionCode().trim().toUpperCase(Locale.ROOT));
		question.setCertificationLevel(request.certificationLevel());
		question.setQuestionCategory(request.questionCategory().databaseValue());
		question.setQuestionType(request.questionType().databaseValue());
		question.setQuestionText(request.questionText().trim());
		question.setOptionsJson(writeAsJson(request.options()));
		question.setCorrectOptionsJson(writeAsJson(request.correctOptions()));
		question.setSeverity(request.severity());
		question.setMarks(request.marks());
		question.setActive(request.active());
		return question;
	}

	private QuestionResponse toResponse(Question question) {
		return new QuestionResponse(
				question.getId(),
				question.getQuestionCode(),
				question.getCertificationLevel(),
				QuestionCategory.valueOf(question.getQuestionCategory().toUpperCase(Locale.ROOT)),
				parseQuestionType(question.getQuestionType()),
				question.getQuestionText(),
				readAsList(question.getOptionsJson()),
				readAsList(question.getCorrectOptionsJson()),
				question.getSeverity(),
				question.getMarks(),
				question.isActive(),
				question.getCreatedDate().toInstant(ZoneOffset.UTC),
				question.getUpdatedDate() == null ? null : question.getUpdatedDate().toInstant(ZoneOffset.UTC));
	}

	private QuestionType parseQuestionType(String databaseValue) {
		return switch (databaseValue) {
			case "Single Choice" -> QuestionType.SINGLE_CHOICE;
			case "Multiple Choice" -> QuestionType.MULTIPLE_CHOICE;
			default -> throw new BusinessException("Unsupported question type: " + databaseValue);
		};
	}

	private String writeAsJson(List<String> values) {
		try {
			return objectMapper.writeValueAsString(values);
		} catch (JsonProcessingException ex) {
			throw new BusinessException("Failed to serialize question options", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private List<String> readAsList(String json) {
		try {
			return objectMapper.readValue(json, STRING_LIST_TYPE);
		} catch (JsonProcessingException ex) {
			throw new BusinessException("Failed to deserialize question options", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
