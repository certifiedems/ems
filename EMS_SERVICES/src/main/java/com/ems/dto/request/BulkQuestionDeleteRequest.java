package com.ems.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record BulkQuestionDeleteRequest(
        @NotEmpty List<@NotNull Long> questionIds) {
}
