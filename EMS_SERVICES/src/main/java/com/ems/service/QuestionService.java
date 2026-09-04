package com.ems.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.ems.dto.request.QuestionUpsertRequest;
import com.ems.dto.response.BulkQuestionDeleteResponse;
import com.ems.dto.response.BulkQuestionUploadResponse;
import com.ems.dto.response.QuestionResponse;
import com.ems.enums.CertificationLevel;
import com.ems.enums.QuestionSeverity;

public interface QuestionService {

    QuestionResponse create(QuestionUpsertRequest request);

    QuestionResponse getById(Long questionId);

    QuestionResponse update(Long questionId, QuestionUpsertRequest request);

    void delete(Long questionId);

    BulkQuestionDeleteResponse bulkDelete(List<Long> questionIds);

    List<QuestionResponse> search(String questionCode, CertificationLevel certificationLevel,
            QuestionSeverity severity, Boolean active, String searchText);

    BulkQuestionUploadResponse bulkUpload(MultipartFile file);
}
