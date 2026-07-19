package com.salesmanager.crm.leadimport.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Parses {@code .xlsx} files (only the OOXML format - no legacy {@code .xls}/HSSF support,
 * matching the feature spec) via Apache POI. Only the FIRST sheet is read - a bulk lead-import
 * template is expected to be a single flat table, not a multi-sheet workbook.
 *
 * {@link DataFormatter} converts every cell (numeric, date, formula-result, etc.) to its
 * displayed text the same way Excel itself would render it, so callers downstream never need
 * to branch on {@code CellType} - everything is just cell text, same as a CSV file would give.
 */
@Component
public class ExcelSpreadsheetParser implements SpreadsheetParser {

    @Override
    public List<List<String>> parse(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream inputStream = file.getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                return rows;
            }
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int lastCellNum = row.getLastCellNum(); // -1 for a completely empty row
                for (int c = 0; c < lastCellNum; c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                if (cells.stream().anyMatch(v -> !v.isBlank())) {
                    rows.add(cells);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // POI throws several RuntimeExceptions (not IOException) for corrupt/malformed
            // OOXML content (e.g. POIXMLException, NotOfficeXmlFileException in some POI
            // versions) - normalized to IOException here so LeadImportService's single
            // catch(IOException) around parser.parse(...) handles every "couldn't read this
            // file" case uniformly, regardless of which parser was used.
            throw new IOException("Failed to parse Excel (.xlsx) file: " + e.getMessage(), e);
        }
        return rows;
    }
}
