package com.ems.dto.response;

import java.util.List;

public record BulkQuestionDeleteResponse(
        int totalRequested,
        int deletedCount,
        int failedCount,
        List<String> errors) {
}
