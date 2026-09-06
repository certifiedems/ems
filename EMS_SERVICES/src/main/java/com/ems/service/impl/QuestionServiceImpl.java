package com.ems.service.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

	private static final Pattern QUESTION_CODE_PATTERN = Pattern.compile("^(L[123])([LMH])\\d{3,}$", Pattern.CASE_INSENSITIVE);
	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};

	private final QuestionRepository questionRepository;
	private final ObjectMapper objectMapper;
	private final AuditService auditService;

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

	@Override
	@CacheEvict(cacheNames = { "questionById", "questionSearch", "reports" }, allEntries = true)
	public BulkQuestionUploadResponse bulkUpload(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessException("Bulk upload file is required", HttpStatus.BAD_REQUEST);
		}

		int totalRows = 0;
		int importedRows = 0;
		List<String> errors = new ArrayList<>();

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			boolean headerSkipped = false;

			while ((line = reader.readLine()) != null) {
				if (!headerSkipped && line.toLowerCase(Locale.ROOT).contains("quesid")) {
					headerSkipped = true;
					continue;
				}
				if (line.isBlank()) {
					continue;
				}

				totalRows++;
				try {
					QuestionUpsertRequest request = parseBulkRow(line);
					if (questionRepository.existsByQuestionCodeIgnoreCase(request.questionCode())) {
						update(
								questionRepository.findByQuestionCodeIgnoreCase(request.questionCode())
										.orElseThrow()
										.getId(),
								request);
					} else {
						create(request);
					}
					importedRows++;
				} catch (Exception ex) {
					errors.add("Row " + totalRows + ": " + ex.getMessage());
				}
			}
		} catch (IOException ex) {
			throw new BusinessException("Failed to read bulk upload file", HttpStatus.BAD_REQUEST);
		}

		// One summary row for the batch as a whole; each imported/updated question
		// also logged its own ADMIN_ACTION above via create()/update(), so a
		// specific bad row within a batch is still traceable on its own.
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(errors.isEmpty() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
				.targetType("QUESTION")
				.description("Bulk uploaded questions: totalRows=" + totalRows
						+ ", imported=" + importedRows + ", failed=" + errors.size())
				.build());

		return new BulkQuestionUploadResponse(totalRows, importedRows, errors.size(), errors);
	}

	private QuestionUpsertRequest parseBulkRow(String line) {
		String[] tokens = line.split(",", -1);
		if (tokens.length < 8) {
			throw new BusinessException(
					"Bulk upload row must contain at least 8 columns: quesID, question, option1, option2, option3, option4, answer, severity");
		}

		String questionCode = tokens[0].trim();
		CertificationLevel level = parseLevelFromQuestionCode(questionCode);
		String questionText = tokens[1].trim();

		List<String> options = List.of(
				tokens[2].trim(),
				tokens[3].trim(),
				tokens[4].trim(),
				tokens[5].trim());

		List<String> correctOptions = Arrays.stream(tokens[6].split("\\|"))
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.toList();

		QuestionSeverity severity = QuestionSeverity.valueOf(tokens[7].trim().toUpperCase(Locale.ROOT));
		QuestionType questionType = correctOptions.size() > 1
				? QuestionType.MULTIPLE_CHOICE
				: QuestionType.SINGLE_CHOICE;

		QuestionCategory questionCategory = (tokens.length > 8 && !tokens[8].trim().isBlank())
				? QuestionCategory.valueOf(tokens[8].trim().toUpperCase(Locale.ROOT))
				: QuestionCategory.GENERAL;

		BigDecimal marks = (tokens.length > 9 && !tokens[9].trim().isBlank())
				? new BigDecimal(tokens[9].trim())
				: BigDecimal.ONE;

		return new QuestionUpsertRequest(
				questionCode,
				level,
				questionCategory,
				questionType,
				questionText,
				options,
				correctOptions,
				severity,
				marks,
				true);
	}

	private void validateQuestionRequest(QuestionUpsertRequest request) {
		Matcher matcher = QUESTION_CODE_PATTERN.matcher(request.questionCode().trim().toUpperCase(Locale.ROOT));
		if (!matcher.matches()) {
			throw new BusinessException("Question code must match format like L1L001, L2M001, or L3H001");
		}

		CertificationLevel levelFromCode = CertificationLevel.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
		String severityCode = matcher.group(2).toUpperCase(Locale.ROOT);

		if (levelFromCode != request.certificationLevel()) {
			throw new BusinessException("Question code level does not match certification level");
		}
		if (!severityCode.equals(request.severity().code())) {
			throw new BusinessException("Question code severity marker does not match severity");
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
				throw new BusinessException("Each correct option must match one of the provided options");
			}
		}
	}

	private CertificationLevel parseLevelFromQuestionCode(String questionCode) {
		Matcher matcher = QUESTION_CODE_PATTERN.matcher(questionCode.trim().toUpperCase(Locale.ROOT));
		if (!matcher.matches()) {
			throw new BusinessException("Invalid question code format");
		}
		return CertificationLevel.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
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
