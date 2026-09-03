package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ems.dto.request.ExamUpsertRequest;
import com.ems.dto.response.ExamResponse;
import com.ems.entity.Exam;
import com.ems.enums.CertificationLevel;
import com.ems.exception.BusinessException;
import com.ems.repository.ExamRepository;

/**
 * Covers the question blueprint an admin sets alongside the pass mark.
 *
 * <p>The mix is three numbers that only mean something together, so the checks
 * that matter are the ones no single-field annotation can make: that the shares
 * describe a whole paper, and that a caller cannot set half of one.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExamServiceImplTest {

    @Mock
    private ExamRepository examRepository;

    @InjectMocks
    private ExamServiceImpl examService;

    private static ExamUpsertRequest request(Integer totalQuestions, String low, String medium, String high) {
        return new ExamUpsertRequest(
                "L1-FOUND-001",
                "Level 1 Foundation Certification Exam",
                CertificationLevel.L1,
                60,
                new BigDecimal("30.00"),
                new BigDecimal("70.00"),
                totalQuestions,
                low == null ? null : new BigDecimal(low),
                medium == null ? null : new BigDecimal(medium),
                high == null ? null : new BigDecimal(high));
    }

    private void stubSave() {
        when(examRepository.existsByExamCodeIgnoreCase(any())).thenReturn(false);
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("stores the mix the admin set and reports the paper it builds")
    void storesBlueprintAndDerivesCounts() {
        stubSave();

        ExamResponse response = examService.create(request(30, "30.00", "40.00", "30.00"));

        assertThat(response.totalQuestions()).isEqualTo(30);
        assertThat(response.lowSeverityPercentage()).isEqualByComparingTo("30.00");
        assertThat(response.mediumSeverityPercentage()).isEqualByComparingTo("40.00");
        assertThat(response.highSeverityPercentage()).isEqualByComparingTo("30.00");
        assertThat(response.lowSeverityQuestions()).isEqualTo(9);
        assertThat(response.mediumSeverityQuestions()).isEqualTo(12);
        assertThat(response.highSeverityQuestions()).isEqualTo(9);
        assertThat(response.passingPercentage()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("rejects a mix that does not add up to 100")
    void rejectsUnbalancedMix() {
        assertThatThrownBy(() -> examService.create(request(30, "30.00", "40.00", "20.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("add up to 100");

        verify(examRepository, never()).save(any(Exam.class));
    }

    @Test
    @DisplayName("rejects a half-set blueprint rather than filling the gaps with defaults")
    void rejectsPartialBlueprint() {
        assertThatThrownBy(() -> examService.create(request(30, "50.00", null, "50.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("incomplete");

        verify(examRepository, never()).save(any(Exam.class));
    }

    @Test
    @DisplayName("a request that sets no blueprint at all gets the standard paper")
    void fallsBackToStandardPaper() {
        stubSave();

        ExamResponse response = examService.create(request(null, null, null, null));

        assertThat(response.totalQuestions()).isEqualTo(30);
        assertThat(response.lowSeverityQuestions()).isEqualTo(6);
        assertThat(response.mediumSeverityQuestions()).isEqualTo(12);
        assertThat(response.highSeverityQuestions()).isEqualTo(12);
    }

    @Test
    @DisplayName("percentages are rounded to the scale the column stores before being used")
    void roundsPercentagesToStoredScale() {
        stubSave();

        // 33.333 + 33.333 + 33.334 rounds to 33.33 + 33.33 + 33.33, which is 99.99.
        assertThatThrownBy(() -> examService.create(request(30, "33.333", "33.333", "33.334")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("add up to 100");
    }

    @Test
    @DisplayName("editing an exam writes the new mix onto it")
    void updateOverwritesBlueprint() {
        Exam existing = Exam.builder()
                .id(7L)
                .examCode("L1-FOUND-001")
                .certificationLevel(CertificationLevel.L1)
                .build();
        when(examRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(examRepository.findByExamCodeIgnoreCase(any())).thenReturn(Optional.of(existing));
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        examService.update(7L, request(20, "25.00", "50.00", "25.00"));

        ArgumentCaptor<Exam> saved = ArgumentCaptor.forClass(Exam.class);
        verify(examRepository).save(saved.capture());
        assertThat(saved.getValue().getTotalQuestions()).isEqualTo(20);
        assertThat(saved.getValue().getLowSeverityPercentage()).isEqualByComparingTo("25.00");
        assertThat(saved.getValue().getMediumSeverityPercentage()).isEqualByComparingTo("50.00");
        assertThat(saved.getValue().getHighSeverityPercentage()).isEqualByComparingTo("25.00");
    }
}
