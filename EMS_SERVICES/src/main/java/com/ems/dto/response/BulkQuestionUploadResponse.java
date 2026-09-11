package com.ems.dto.response;

import java.util.List;

public record BulkQuestionUploadResponse(
        int totalRows,
        int importedRows,
        int createdRows,
        int updatedRows,
        int failedRows,
        List<String> errors) {
}
