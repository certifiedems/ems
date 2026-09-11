package com.ems.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.multipart.MultipartFile;

import com.ems.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads a question bulk-upload file (.xlsx, .xls or .csv) into rows keyed by
 * column.
 *
 * <p>Columns are found by their header name, so their order in the file does not
 * matter. A file without a header row is read in the order the columns are
 * declared below.</p>
 */
@Slf4j
public final class QuestionBulkFileReader {

    public enum Column {
        QUES_ID("quesID", true, "quesid", "questionid", "questioncode", "code"),
        QUESTION("question", true, "question", "questiontext"),
        OPTION1("option1", true, "option1"),
        OPTION2("option2", true, "option2"),
        OPTION3("option3", true, "option3"),
        OPTION4("option4", true, "option4"),
        ANSWER("answer", true, "answer", "answers", "correctanswer", "correctoption", "correctoptions"),
        SEVERITY("severity", true, "severity"),
        CATEGORY("category", false, "category", "questioncategory"),
        MARKS("marks", false, "marks", "mark"),
        LEVEL("Level", false, "level", "certificationlevel");

        private final String headerName;
        private final boolean required;
        private final List<String> aliases;

        Column(String headerName, boolean required, String... aliases) {
            this.headerName = headerName;
            this.required = required;
            this.aliases = List.of(aliases);
        }

        public String headerName() {
            return headerName;
        }

        /** Matches "quesID", "Ques ID" and "ques_id" alike; null for an unknown header. */
        private static Column fromHeader(String header) {
            String normalized = header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            return Arrays.stream(values())
                    .filter(column -> column.aliases.contains(normalized))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** A data row; {@code label} says where it sits in the file, e.g. "Row 12". */
    public record QuestionRow(String label, Map<Column, String> values) {

        public String get(Column column) {
            return values.getOrDefault(column, "");
        }
    }

    private record RawRow(int rowNumber, List<String> cells) {

        boolean isBlank() {
            return cells.stream().allMatch(String::isEmpty);
        }

        boolean isHeader() {
            return cells.stream().anyMatch(cell -> Column.fromHeader(cell) == Column.QUES_ID);
        }
    }

    private static final String EXPECTED_COLUMNS = Arrays.stream(Column.values())
            .map(Column::headerName)
            .collect(Collectors.joining(", "));

    private QuestionBulkFileReader() {
    }

    public static List<QuestionRow> read(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename().toLowerCase(Locale.ROOT);

        // The content decides the format, so a workbook saved under the wrong
        // extension still reads as a workbook.
        try (InputStream in = FileMagic.prepareToCheckMagic(file.getInputStream())) {
            FileMagic magic = FileMagic.valueOf(in);
            if (magic == FileMagic.OOXML || magic == FileMagic.OLE2) {
                return readWorkbook(in);
            }
            if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
                throw new BusinessException("The uploaded file is not a valid Excel workbook");
            }
            if (fileName.contains(".") && !fileName.endsWith(".csv")) {
                throw new BusinessException("Unsupported file type. Upload an .xlsx, .xls or .csv file");
            }
            return readCsv(in);
        }
    }

    private static List<QuestionRow> readWorkbook(InputStream in) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            // Formula cells are read from the result Excel saved with the file rather
            // than re-evaluated here, where functions POI does not implement would fail.
            DataFormatter formatter = new DataFormatter(Locale.US);
            formatter.setUseCachedValuesForFormulaCells(true);

            Map<String, List<RawRow>> rowsBySheet = new LinkedHashMap<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) {
                    continue;
                }
                Sheet sheet = workbook.getSheetAt(index);
                rowsBySheet.put(sheet.getSheetName(), readSheet(sheet, formatter));
            }

            // Every sheet that starts with a quesID header is imported, so a notes
            // tab beside the questions is ignored. With no header on any sheet, the
            // first sheet is read in the default column order.
            Map<String, List<RawRow>> questionSheets = new LinkedHashMap<>();
            rowsBySheet.forEach((sheetName, rows) -> {
                if (rows.stream().filter(row -> !row.isBlank()).findFirst().map(RawRow::isHeader).orElse(false)) {
                    questionSheets.put(sheetName, rows);
                }
            });
            if (questionSheets.isEmpty()) {
                return rowsBySheet.values().stream()
                        .findFirst()
                        .map(rows -> toQuestionRows(rows, ""))
                        .orElse(List.of());
            }

            boolean nameSheets = questionSheets.size() > 1;
            List<QuestionRow> questionRows = new ArrayList<>();
            questionSheets.forEach((sheetName, rows) -> questionRows.addAll(
                    toQuestionRows(rows, nameSheets ? "Sheet '" + sheetName + "' " : "")));
            return questionRows;
        } catch (EncryptedDocumentException ex) {
            throw new BusinessException("Password-protected workbooks are not supported; remove the password and upload again");
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // POI reports most unreadable files (a zip that is not a workbook, an
            // Apple Numbers export, a damaged sheet) as unchecked exceptions, which
            // would otherwise reach the caller as a bare 500.
            log.warn("Bulk upload workbook could not be read", ex);
            throw new BusinessException("The uploaded file could not be read as an Excel workbook: "
                    + ex.getMessage() + ". Save it as .xlsx and upload again");
        }
    }

    private static List<RawRow> readSheet(Sheet sheet, DataFormatter formatter) {
        List<RawRow> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int index = 0; index < row.getLastCellNum(); index++) {
                Cell cell = row.getCell(index);
                // Read as Excel displays it, so a marks cell of 1 is "1" rather than "1.0".
                cells.add(cell == null ? "" : clean(formatter.formatCellValue(cell)));
            }
            rows.add(new RawRow(row.getRowNum() + 1, cells));
        }
        return rows;
    }

    private static List<QuestionRow> readCsv(InputStream in) throws IOException {
        // Blank lines are kept as records so record numbers stay in step with line
        // numbers; toQuestionRows drops them.
        CSVFormat format = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(false).build();
        List<RawRow> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8), format)) {
            for (CSVRecord record : parser) {
                rows.add(new RawRow((int) record.getRecordNumber(),
                        record.stream().map(QuestionBulkFileReader::clean).toList()));
            }
        } catch (UncheckedIOException | IllegalStateException ex) {
            throw new BusinessException("The CSV file could not be read; check for a missing closing quote");
        }
        return toQuestionRows(rows, "");
    }

    private static List<QuestionRow> toQuestionRows(List<RawRow> rows, String labelPrefix) {
        List<RawRow> dataRows = rows.stream().filter(row -> !row.isBlank()).toList();
        if (dataRows.isEmpty()) {
            return List.of();
        }

        List<Column> columns;
        if (dataRows.get(0).isHeader()) {
            columns = headerColumns(dataRows.get(0));
            dataRows = dataRows.subList(1, dataRows.size());
        } else {
            columns = List.of(Column.values());
        }

        List<QuestionRow> questionRows = new ArrayList<>();
        for (RawRow row : dataRows) {
            Map<Column, String> values = new EnumMap<>(Column.class);
            for (int index = 0; index < Math.min(columns.size(), row.cells().size()); index++) {
                if (columns.get(index) != null) {
                    values.putIfAbsent(columns.get(index), row.cells().get(index));
                }
            }
            String label = labelPrefix.isEmpty()
                    ? "Row " + row.rowNumber()
                    : labelPrefix + "row " + row.rowNumber();
            questionRows.add(new QuestionRow(label, values));
        }
        return questionRows;
    }

    /** Maps each header cell to its column (null for unknown headers). */
    private static List<Column> headerColumns(RawRow header) {
        List<Column> columns = header.cells().stream().map(Column::fromHeader).toList();
        String missing = Arrays.stream(Column.values())
                .filter(column -> column.required && !columns.contains(column))
                .map(Column::headerName)
                .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new BusinessException("Bulk upload file is missing column(s): " + missing
                    + ". Expected columns: " + EXPECTED_COLUMNS);
        }
        return columns;
    }

    /** Drops the byte-order mark and non-breaking spaces that spreadsheet exports carry. */
    private static String clean(String value) {
        return value == null ? "" : value.replace("\uFEFF", "").replace('\u00A0', ' ').strip();
    }
}
