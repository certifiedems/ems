package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import com.ems.dto.response.BulkQuestionUploadResponse;
import com.ems.entity.Question;
import com.ems.enums.CertificationLevel;
import com.ems.enums.QuestionSeverity;
import com.ems.exception.BusinessException;
import com.ems.repository.QuestionRepository;
import com.ems.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Covers bulk upload of the question sheet admins maintain in Excel: quesID such
 * as Q008M (severity marker last), with the level in its own column.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionServiceImplTest {

    private static final String[] HEADER = {
            "quesID", "question", "option1", "option2", "option3", "option4",
            "answer", "severity", "category", "marks", "Level" };

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private AuditService auditService;

    private QuestionServiceImpl questionService;

    /** Stands in for the questions table, keyed by code as its unique index is. */
    private final Map<String, Question> savedByCode = new HashMap<>();

    @BeforeEach
    void setUp() {
        questionService = new QuestionServiceImpl(questionRepository, new ObjectMapper(), auditService);

        when(questionRepository.existsByQuestionCodeIgnoreCase(anyString()))
                .thenAnswer(inv -> savedByCode.containsKey(inv.<String>getArgument(0).toUpperCase(Locale.ROOT)));
        when(questionRepository.findByQuestionCodeIgnoreCase(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(savedByCode.get(inv.<String>getArgument(0).toUpperCase(Locale.ROOT))));
        when(questionRepository.findById(any()))
                .thenAnswer(inv -> savedByCode.values().stream()
                        .filter(question -> question.getId().equals(inv.getArgument(0)))
                        .findFirst());
        when(questionRepository.save(any(Question.class))).thenAnswer(inv -> {
            Question question = inv.getArgument(0);
            if (question.getId() == null) {
                question.setId((long) savedByCode.size() + 1);
                question.setCreatedDate(LocalDateTime.now());
            }
            savedByCode.put(question.getQuestionCode(), question);
            return question;
        });
    }

    @Test
    @DisplayName("an Excel sheet in the quesID + Level format imports every question")
    void importsExcelSheetInQuesIdLevelFormat() throws IOException {
        MockMultipartFile file = xlsx(HEADER,
                new Object[] { "Q001L", "What does Ohm's Law state?",
                        "V = IR", "V = I/R", "V = I+R", "V = I-R", "V = IR", "LOW", "TECHNICAL", 1, "L1" },
                new Object[] { "Q008M", "If voltage is 12V and resistance is 4 ohms, what is the current?",
                        "2A", "3A", "4A", "48A", "3A", "MEDIUM", "TECHNICAL", 1, "L1" },
                new Object[] { "Q017M", "What is electrical energy measured in for billing purposes?",
                        "Watt", "Joule", "kWh (kilowatt-hour)", "Ampere-hour", "kwh (kilowatt-hour)", "MEDIUM", "GENERAL", 1, "L1" },
                // Excel often leaves formatted but empty rows under a table.
                new Object[] { "", "", "" },
                new Object[] { "Q018H", "Which formula correctly relates power, voltage and resistance?",
                        "P = V^2/R", "P = R/V^2", "P = V/R^2", "P = R^2/V", "P = V^2/R", "HIGH", "TECHNICAL", 2, "L2" });

        BulkQuestionUploadResponse response = questionService.bulkUpload(file);

        assertThat(response.errors()).isEmpty();
        assertThat(response.totalRows()).isEqualTo(4);
        assertThat(response.createdRows()).isEqualTo(4);

        Question power = savedByCode.get("Q018H");
        assertThat(power.getCertificationLevel()).isEqualTo(CertificationLevel.L2);
        assertThat(power.getSeverity()).isEqualTo(QuestionSeverity.HIGH);
        assertThat(power.getMarks()).isEqualByComparingTo("2");

        Question energy = savedByCode.get("Q017M");
        assertThat(energy.getQuestionCategory()).isEqualTo("General");
        assertThat(energy.getCorrectOptionsJson()).isEqualTo("[\"kWh (kilowatt-hour)\"]");
    }

    @Test
    @DisplayName("a CSV saved from Excel keeps commas inside quoted cells")
    void importsCsvWithQuotedCommasAndByteOrderMark() {
        String csv = "\uFEFF" + String.join(",", HEADER) + "\r\n"
                + "Q008M,\"If voltage is 12V and resistance is 4 ohms, what is the current?\",2A,3A,4A,48A,3A,MEDIUM,TECHNICAL,1,L1\r\n";

        BulkQuestionUploadResponse response = questionService.bulkUpload(csv("questions.csv", csv));

        assertThat(response.errors()).isEmpty();
        assertThat(savedByCode.get("Q008M").getQuestionText())
                .isEqualTo("If voltage is 12V and resistance is 4 ohms, what is the current?");
    }

    @Test
    @DisplayName("older L1L001 codes still import without a header or Level column")
    void stillImportsLevelCodesWithoutHeader() {
        String csv = "L2M014,Which quantity remains constant in a series circuit?,Voltage,Current,Power,Resistance,Current,MEDIUM,TECHNICAL,1\n";

        BulkQuestionUploadResponse response = questionService.bulkUpload(csv("questions.csv", csv));

        assertThat(response.errors()).isEmpty();
        assertThat(savedByCode.get("L2M014").getCertificationLevel()).isEqualTo(CertificationLevel.L2);
    }

    @Test
    @DisplayName("each bad row is reported against its sheet row, and the good rows still import")
    void reportsBadRowsBySheetRowAndImportsTheRest() throws IOException {
        MockMultipartFile file = xlsx(HEADER,
                row("Q001L", "LOW", "L1"),
                row("Q002L", "MEDIUM", "L1"),
                row("Q003L", "LOW", ""),
                row("Q001L", "LOW", "L1"),
                row("Q004X", "LOW", "L1"),
                row("Q005L", "LOW", "L4"));

        BulkQuestionUploadResponse response = questionService.bulkUpload(file);

        assertThat(response.createdRows()).isEqualTo(1);
        assertThat(response.failedRows()).isEqualTo(5);
        assertThat(response.errors()).containsExactly(
                "Row 3 (Q002L): Question code severity marker (L) does not match severity MEDIUM",
                "Row 4 (Q003L): Level is required (L1, L2 or L3)",
                "Row 5 (Q001L): quesID is repeated in this file (first used on Row 2)",
                "Row 6 (Q004X): Question code must match format like Q001L, Q008M or Q018H (or L1L001)",
                "Row 7 (Q005L): Invalid Level 'L4'; expected L1, L2 or L3");
    }

    @Test
    @DisplayName("re-uploading a quesID updates it, but never moves it to another level")
    void updatesExistingQuestionOnlyWithinItsLevel() throws IOException {
        questionService.bulkUpload(xlsx(HEADER, row("Q001L", "LOW", "L1"), row("Q002L", "LOW", "L1")));

        BulkQuestionUploadResponse response = questionService.bulkUpload(
                xlsx(HEADER, row("Q001L", "LOW", "L2"), row("Q002L", "LOW", "L1")));

        assertThat(response.updatedRows()).isEqualTo(1);
        assertThat(response.errors()).containsExactly(
                "Row 2 (Q001L): quesID already exists for level L1; use a different quesID for this L2 question");
        assertThat(savedByCode.get("Q001L").getCertificationLevel()).isEqualTo(CertificationLevel.L1);
    }

    @Test
    @DisplayName("a sheet missing required columns is rejected before any row is saved")
    void rejectsSheetMissingRequiredColumns() throws IOException {
        MockMultipartFile file = xlsx(
                new String[] { "quesID", "question", "option1", "option2", "option3", "option4", "Level" },
                row("Q001L", "LOW", "L1"));

        assertThatThrownBy(() -> questionService.bulkUpload(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("missing column(s): answer, severity");
        verify(questionRepository, never()).save(any());
    }

    @Test
    void rejectsFilesThatAreNotExcelOrCsv() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "questions.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> questionService.bulkUpload(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported file type");
    }

    private static Object[] row(String questionCode, String severity, String level) {
        return new Object[] { questionCode, "Which is B?", "A", "B", "C", "D", "B", severity, "TECHNICAL", 1, level };
    }

    private static MockMultipartFile csv(String fileName, String content) {
        return new MockMultipartFile("file", fileName, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile xlsx(String[] header, Object[]... rows) throws IOException {
        List<Object[]> sheetRows = new ArrayList<>();
        sheetRows.add(header);
        sheetRows.addAll(List.of(rows));

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Questions");
            for (int rowIndex = 0; rowIndex < sheetRows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex);
                Object[] values = sheetRows.get(rowIndex);
                for (int col = 0; col < values.length; col++) {
                    if (values[col] instanceof Number number) {
                        row.createCell(col).setCellValue(number.doubleValue());
                    } else {
                        row.createCell(col).setCellValue(String.valueOf(values[col]));
                    }
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", "questions.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}
