package com.salesmanager.crm.leadimport.parser;

import java.io.IOException;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * Parses an uploaded spreadsheet-like file into a generic grid of cell text, used identically
 * by both the preview and commit steps of Lead bulk import (LeadImportService dispatches to
 * either {@link ExcelSpreadsheetParser} or {@link CsvSpreadsheetParser} by filename extension).
 * Row 0 is always the header row, if present. Fully-blank rows (every cell blank/whitespace) -
 * a common trailing artifact in both Excel sheets and CSV files - are dropped entirely rather
 * than surfaced as data rows, by both implementations.
 */
public interface SpreadsheetParser {

    List<List<String>> parse(MultipartFile file) throws IOException;
}
