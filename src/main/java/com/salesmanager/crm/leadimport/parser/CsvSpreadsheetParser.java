package com.salesmanager.crm.leadimport.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Hand-rolled RFC 4180 CSV parser - deliberately not a library dependency, mirroring how this
 * codebase already hand-rolls CSV EXPORT on the frontend (see
 * frontend/src/utils/exportToCsv.ts#escapeCsvField); this is that same escaping scheme applied
 * in reverse. A field is quoted if it contains a comma, a double quote, or a newline; an
 * embedded double quote is doubled ({@code ""}); a quoted field may itself contain raw
 * newlines, which is exactly why this is a character-by-character state machine over the whole
 * file content rather than a naive line-by-line split (a `String.split("\n")` would incorrectly
 * break a single logical row in the middle of a quoted multi-line field).
 */
@Component
public class CsvSpreadsheetParser implements SpreadsheetParser {

    private static final char BOM = (char) 0xFEFF;

    @Override
    public List<List<String>> parse(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        // Strip a UTF-8 BOM if present (common when a CSV is exported from Excel/Windows tools).
        if (!content.isEmpty() && content.charAt(0) == BOM) {
            content = content.substring(1);
        }

        List<List<String>> rawRows = parseRfc4180(content);

        List<List<String>> rows = new ArrayList<>();
        for (List<String> rawRow : rawRows) {
            List<String> trimmedRow = new ArrayList<>(rawRow.size());
            boolean anyNonBlank = false;
            for (String cell : rawRow) {
                String trimmed = cell == null ? "" : cell.trim();
                if (!trimmed.isEmpty()) {
                    anyNonBlank = true;
                }
                trimmedRow.add(trimmed);
            }
            if (anyNonBlank) {
                rows.add(trimmedRow);
            }
        }
        return rows;
    }

    private List<List<String>> parseRfc4180(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int length = content.length();

        while (i < length) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }

            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    i++;
                }
                case ',' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    i++;
                }
                case '\r' -> {
                    i++;
                    if (i < length && content.charAt(i) == '\n') {
                        i++;
                    }
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
                case '\n' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    i++;
                }
                default -> {
                    field.append(c);
                    i++;
                }
            }
        }
        // Trailing field/row for files that don't end with a final newline.
        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow);
        }
        return rows;
    }
}
