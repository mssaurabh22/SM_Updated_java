package com.salesmanager.crm.leadimport.dto;

/**
 * One row that could not be imported for a reason OTHER than being a duplicate (see
 * {@link LeadImportSkippedDuplicate} for that case) - e.g. a missing required field. {@code
 * rowNumber} is the 1-based row number as it would appear in the actual spreadsheet/CSV file
 * (row 1 is the header, so the first data row is row 2) - matches what an admin sees if they
 * open the file themselves to fix it up and re-import.
 */
public record LeadImportRowError(int rowNumber, String message) {
}
