package com.ems.controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ems.dto.request.BulkQuestionDeleteRequest;
import com.ems.dto.request.QuestionUpsertRequest;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.BulkQuestionDeleteResponse;
import com.ems.dto.response.BulkQuestionUploadResponse;
import com.ems.dto.response.QuestionResponse;
import com.ems.enums.CertificationLevel;
import com.ems.enums.QuestionSeverity;
import com.ems.service.QuestionService;
import com.ems.util.CorrelationIdUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/questions")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class QuestionController {

    private final QuestionService questionService;

    @PostMapping
    public ResponseEntity<ApiResponse<QuestionResponse>> create(
            @Valid @RequestBody QuestionUpsertRequest request) {
        return ok("Question created successfully", questionService.create(request));
    }

    @GetMapping("/{questionId}")
    public ResponseEntity<ApiResponse<QuestionResponse>> getById(@PathVariable Long questionId) {
        return ok("Question fetched successfully", questionService.getById(questionId));
    }

    @PutMapping("/{questionId}")
    public ResponseEntity<ApiResponse<QuestionResponse>> update(
            @PathVariable Long questionId,
            @Valid @RequestBody QuestionUpsertRequest request) {
        return ok("Question updated successfully", questionService.update(questionId, request));
    }

    @DeleteMapping("/{questionId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long questionId) {
        questionService.delete(questionId);
        return ok("Question deleted successfully", null);
    }

    @PostMapping("/bulk-delete")
    public ResponseEntity<ApiResponse<BulkQuestionDeleteResponse>> bulkDelete(
            @Valid @RequestBody BulkQuestionDeleteRequest request) {
        return ok("Bulk delete completed", questionService.bulkDelete(request.questionIds()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<QuestionResponse>>> search(
            @RequestParam(required = false) String questionCode,
            @RequestParam(required = false) CertificationLevel certificationLevel,
            @RequestParam(required = false) QuestionSeverity severity,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String search) {
        return ok("Questions fetched successfully",
                questionService.search(questionCode, certificationLevel, severity, active, search));
    }

    @PostMapping("/bulk-upload")
    public ResponseEntity<ApiResponse<BulkQuestionUploadResponse>> bulkUpload(
            @RequestParam("file") MultipartFile file) {
        return ok("Bulk upload completed", questionService.bulkUpload(file));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.success(message, data, CorrelationIdUtil.getOrCreateTraceId()));
    }
}
