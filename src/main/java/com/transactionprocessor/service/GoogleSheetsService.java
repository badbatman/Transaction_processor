package com.transactionprocessor.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetResponse;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.DuplicateSheetRequest;
import com.google.api.services.sheets.v4.model.ExtendedValue;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.RepeatCellRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.TextFormat;
import com.google.api.services.sheets.v4.model.UpdateCellsRequest;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.transactionprocessor.config.GoogleSheetsConfig;
import com.transactionprocessor.model.GoogleSheetsRow;

/**
 * Service for interacting with Google Sheets API
 */
public class GoogleSheetsService {
    private static final Logger logger = LoggerFactory.getLogger(GoogleSheetsService.class);
    
    private final GoogleSheetsConfig config;
    private final Sheets sheetsService;
    
    // Rate limit retry configuration
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long INITIAL_RETRY_DELAY_MS = 2000; // 2 seconds
    private static final long MAX_RETRY_DELAY_MS = 30000; // 30 seconds
    
    // Rate limiting: Google Sheets API limit is 60 requests per minute (TPM)
    // We'll limit to 50 TPM to stay safely under the limit
    private static final long MIN_REQUEST_INTERVAL_MS = 1200; // ~50 requests/minute
    private long lastRequestTime = 0;

    public GoogleSheetsService(GoogleSheetsConfig config) throws IOException, GeneralSecurityException {
        this.config = config;
        this.sheetsService = config.getSheetsService();
    }

    /**
     * Alternate constructor for testing that accepts a Sheets instance directly.
     * If sheetsService is null, API calls that require it should not be executed.
     */
    public GoogleSheetsService(GoogleSheetsConfig config, Sheets sheetsService) {
        this.config = config;
        this.sheetsService = sheetsService;
    }

    /**
     * Execute a Google Sheets API call with exponential backoff retry for rate limits
     */
    private <T> T executeWithRetry(String operationName, Callable<T> apiCall) throws IOException {
        int attempt = 0;
        long delay = INITIAL_RETRY_DELAY_MS;
        
        while (true) {
            try {
                // Apply rate limiting before making the request
                applyRateLimiting();
                
                return apiCall.call();
            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                attempt++;
                
                // Check if it's a rate limit error (429)
                if (e.getStatusCode() == 429 && attempt <= MAX_RETRY_ATTEMPTS) {
                    logger.warn("Rate limit hit for {}, waiting {}ms before retry {}/{}", 
                        operationName, delay, attempt, MAX_RETRY_ATTEMPTS);
                    
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for rate limit", ie);
                    }
                    
                    // Exponential backoff with max cap
                    delay = Math.min(delay * 2, MAX_RETRY_DELAY_MS);
                } else {
                    // Not a rate limit error or max retries exceeded
                    logger.error("{} failed after {} attempts. Status code: {}, Message: {}", 
                        operationName, attempt, e.getStatusCode(), e.getMessage());
                    throw e;
                }
            } catch (Exception e) {
                // Wrap other exceptions as IOException
                throw new IOException("Failed to execute " + operationName, e);
            }
        }
    }
    
    /**
     * Apply rate limiting to stay within Google Sheets API limits
     * Target: 50 requests per minute (safe margin under 60 TPM limit)
     */
    private void applyRateLimiting() throws IOException {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRequest = currentTime - lastRequestTime;
        
        if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS && lastRequestTime > 0) {
            long waitTime = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest;
            logger.trace("Rate limiting: waiting {}ms to maintain {} TPM", 
                waitTime, 60000 / MIN_REQUEST_INTERVAL_MS);
            
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while rate limiting", ie);
            }
        }
        
        lastRequestTime = System.currentTimeMillis();
    }

    /**
     * Read all rows from a sheet
     */
    public List<GoogleSheetsRow> readSheet(String spreadsheetId, String sheetName) throws IOException {
        logger.info("Reading sheet: {} from spreadsheet: {}", sheetName, spreadsheetId);
        // We'll request grid data so we can inspect cell formatting (textFormat)
        String range = sheetName + "!A:T"; // Read columns A to T

        Spreadsheet sheetWithGrid = sheetsService.spreadsheets()
                .get(spreadsheetId)
                .setRanges(List.of(range))
                .setIncludeGridData(true)
                .execute();

        if (sheetWithGrid.getSheets() == null || sheetWithGrid.getSheets().isEmpty()) {
            logger.info("No sheet metadata found for: {}", sheetName);
            return new ArrayList<>();
        }

        // Find the requested sheet by name
        com.google.api.services.sheets.v4.model.Sheet targetSheet = null;
        for (Sheet s : sheetWithGrid.getSheets()) {
            if (s.getProperties() != null && sheetName.equals(s.getProperties().getTitle())) {
                targetSheet = s;
                break;
            }
        }

        if (targetSheet == null || targetSheet.getData() == null || targetSheet.getData().isEmpty()) {
            logger.info("No data found in sheet: {}", sheetName);
            return new ArrayList<>();
        }

        List<GoogleSheetsRow> rows = new ArrayList<>();

        // We assume single GridData block for the requested range
        List<com.google.api.services.sheets.v4.model.RowData> rowDataList = targetSheet.getData().get(0).getRowData();
        if (rowDataList == null) {
            logger.info("No row data found in sheet: {}", sheetName);
            return rows;
        }

        // Determine whether the first row is header by checking its first cell text
        int startRowIndex = 0; // 0-based index within returned grid
        if (!rowDataList.isEmpty()) {
            com.google.api.services.sheets.v4.model.RowData first = rowDataList.get(0);
            if (first != null && first.getValues() != null && !first.getValues().isEmpty()) {
                String firstCell = getStringValueFromCell(first.getValues().get(0));
                if (firstCell != null && isHeaderRow(List.of(firstCell))) {
                    startRowIndex = 1;
                }
            }
        }

        for (int i = startRowIndex; i < rowDataList.size(); i++) {
            com.google.api.services.sheets.v4.model.RowData rowData = rowDataList.get(i);
            List<Object> simpleRow = new ArrayList<>();
            List<CellData> cellDataList = rowData.getValues();
            if (cellDataList != null) {
                for (CellData cd : cellDataList) {
                    simpleRow.add(getCellUserValue(cd));
                }
            }

            int sheetRowNumber = i + 1; // convert 0-based to 1-based sheet row number
            GoogleSheetsRow row = parseRowWithFormatting(simpleRow, sheetRowNumber, cellDataList);
            
            // Filter out empty rows to reduce comparison size and improve performance
            if (row != null && !isRowEmpty(row)) {
                rows.add(row);
            }
        }

        logger.info("Read {} non-empty rows from sheet: {}", rows.size(), sheetName);
        return rows;
    }

    /**
     * Write rows to a sheet starting from the specified row
     * Only updates columns A-I (data columns), preserving formula columns J-Q
     */
    public void writeRows(String spreadsheetId, String sheetName, List<GoogleSheetsRow> rows, int startRow) throws IOException {
        if (rows == null || rows.isEmpty()) {
            logger.info("No rows to write to sheet: {}", sheetName);
            return;
        }

        logger.info("Writing {} rows to sheet: {} starting from row: {}", rows.size(), sheetName, startRow);
        
        // Prepare data for writing - columns A-I and R (10 columns total: A,B,C,D,E,F,G,H,I,R)
        // This preserves formula columns J-Q and other columns S,T
        List<List<Object>> values = new ArrayList<>();
        for (GoogleSheetsRow row : rows) {
            List<Object> rowValues = new ArrayList<>();
            rowValues.add(row.getLabel() != null ? row.getLabel() : "");           // A
            rowValues.add(row.getDomain() != null ? row.getDomain() : "");         // B
            rowValues.add(row.getOpenTime() != null ? row.getOpenTime().toString() : ""); // C
            rowValues.add(row.getOpenPrice() != null ? row.getOpenPrice() : BigDecimal.ZERO);   // D
            rowValues.add(row.getNumberOfStock() != null ? row.getNumberOfStock() : BigDecimal.ZERO); // E
            rowValues.add(row.getOpenFeeTax() != null ? row.getOpenFeeTax() : BigDecimal.ZERO);   // F
            rowValues.add(row.getCloseTime() != null ? row.getCloseTime().toString() : ""); // G
            rowValues.add(row.getClosePrice() != null ? row.getClosePrice() : BigDecimal.ZERO);   // H
            rowValues.add(row.getCloseFeeTax() != null ? row.getCloseFeeTax() : BigDecimal.ZERO); // I
            // Skip columns J-Q (formulas - preserve existing)
            rowValues.add(row.getDescription() != null ? row.getDescription() : ""); // R
            values.add(rowValues);
        }

        // Create the value range - columns A-I and R
        // Note: We need to write to A:I and R separately to skip J-Q
        // First write A-I
        String rangeAI = sheetName + "!A" + startRow + ":I" + (startRow + rows.size() - 1);
        ValueRange bodyAI = new ValueRange()
                .setValues(values.stream()
                    .map(v -> v.subList(0, 9)) // First 9 columns (A-I)
                    .collect(java.util.stream.Collectors.toList()))
                .setMajorDimension("ROWS");

        try {
            sheetsService.spreadsheets().values()
                    .update(spreadsheetId, rangeAI, bodyAI)
                    .setValueInputOption("USER_ENTERED")
                    .execute();

            // Then write column R separately
            List<List<Object>> columnRValues = new ArrayList<>();
            for (List<Object> rowValues : values) {
                columnRValues.add(java.util.Collections.singletonList(rowValues.get(9))); // Column R
            }
            String rangeR = sheetName + "!R" + startRow + ":R" + (startRow + rows.size() - 1);
            ValueRange bodyR = new ValueRange()
                    .setValues(columnRValues)
                    .setMajorDimension("ROWS");

            sheetsService.spreadsheets().values()
                    .update(spreadsheetId, rangeR, bodyR)
                    .setValueInputOption("USER_ENTERED")
                    .execute();

            logger.info("Updated cells in sheet: {} (columns A-I and R, formulas J-Q preserved)", sheetName);
            
            // Apply formatting after data is written
            applyFormatting(spreadsheetId, sheetName, rows, startRow);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            // Check if error is due to exceeding grid limits
            if (e.getStatusCode() == 400 && e.getMessage().contains("exceeds grid limits")) {
                logger.warn("Row {} exceeds grid limits ({}), appending instead", startRow, e.getMessage());
                
                // Fall back to append method
                appendRows(spreadsheetId, sheetName, rows);
            } else {
                throw e; // Re-throw if not a grid limit error
            }
        }
    }

    /**
     * Append rows to the end of the sheet
     * Only appends columns A-I (data columns), preserving formula columns J-Q
     */
    public void appendRows(String spreadsheetId, String sheetName, List<GoogleSheetsRow> rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            logger.info("No rows to append to sheet: {}", sheetName);
            return;
        }

        logger.info("Appending {} rows to sheet: {}", rows.size(), sheetName);
        
        // Prepare data for appending - only columns A-I (9 columns)
        // This preserves formula columns J-Q
        List<List<Object>> values = new ArrayList<>();
        for (GoogleSheetsRow row : rows) {
            List<Object> rowValues = new ArrayList<>();
            rowValues.add(row.getLabel() != null ? row.getLabel() : "");           // A
            rowValues.add(row.getDomain() != null ? row.getDomain() : "");         // B
            rowValues.add(row.getOpenTime() != null ? row.getOpenTime().toString() : ""); // C
            rowValues.add(row.getOpenPrice() != null ? row.getOpenPrice() : BigDecimal.ZERO);   // D
            rowValues.add(row.getNumberOfStock() != null ? row.getNumberOfStock() : BigDecimal.ZERO); // E
            rowValues.add(row.getOpenFeeTax() != null ? row.getOpenFeeTax() : BigDecimal.ZERO);   // F
            rowValues.add(row.getCloseTime() != null ? row.getCloseTime().toString() : ""); // G
            rowValues.add(row.getClosePrice() != null ? row.getClosePrice() : BigDecimal.ZERO);   // H
            rowValues.add(row.getCloseFeeTax() != null ? row.getCloseFeeTax() : BigDecimal.ZERO); // I
            values.add(rowValues);
        }

        // Create the value range - ONLY columns A-I, NOT J-Q (formulas)
        String range = sheetName + "!A:I";
        ValueRange body = new ValueRange()
                .setValues(values)
                .setMajorDimension("ROWS");

        // Execute the append
        AppendValuesResponse result = sheetsService.spreadsheets().values()
                .append(spreadsheetId, range, body)
                .setValueInputOption("USER_ENTERED")
                .execute();

        logger.info("Appended {} rows to sheet: {} (columns A-I only, formulas preserved)", result.getUpdates().getUpdatedRows(), sheetName);
        
        // Apply formatting after data is appended
        // Parse the range string to get start row (format: "SheetName!A100" or "A100")
        String resultRange = result.getUpdates().getUpdatedRange();
        int lastBangIndex = resultRange.lastIndexOf('!');
        int startRow = 1; // default
        if (lastBangIndex >= 0 && lastBangIndex < resultRange.length() - 1) {
            String rowPart = resultRange.substring(lastBangIndex + 1);
            // Extract only the FIRST number from the range (e.g., "A140:I143" -> extract 140 from "A140")
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(rowPart);
            if (matcher.find()) {
                startRow = Integer.parseInt(matcher.group());
            }
        }
        applyFormatting(spreadsheetId, sheetName, rows, startRow);
    }

    /**
     * Update specific cells in the sheet using batch update
     * This preserves formulas in columns that aren't being updated
     */
    public void updateCells(String spreadsheetId, String sheetName, List<CellUpdate> cellUpdates) throws IOException {
        if (cellUpdates == null || cellUpdates.isEmpty()) {
            return;
        }

        logger.info("Updating {} cells in sheet: {}", cellUpdates.size(), sheetName);
        
        // Get the sheet ID
        Integer sheetId = getSheetIdByName(spreadsheetId, sheetName);
        if (sheetId == null) {
            throw new IOException("Sheet not found: " + sheetName);
        }
        
        // Create batch update requests for each cell
        List<Request> requests = new ArrayList<>();
        
        for (CellUpdate update : cellUpdates) {
            // Parse cell reference (e.g., "A5" -> row 4, col 0)
            String cellRef = update.getCellRange();
            int[] coordinates = parseCellReference(cellRef);
            int rowIndex = coordinates[0];
            int colIndex = coordinates[1];
            
            // Create cell data with appropriate type
            CellData cellData = new CellData();
            ExtendedValue value = createExtendedValue(update.getValue());
            cellData.setUserEnteredValue(value);
            
            // Create the update request
            UpdateCellsRequest updateRequest = new UpdateCellsRequest();
            
            GridRange range = new GridRange();
            range.setSheetId(sheetId);
            range.setStartRowIndex(rowIndex);
            range.setEndRowIndex(rowIndex + 1);
            range.setStartColumnIndex(colIndex);
            range.setEndColumnIndex(colIndex + 1);
            
            List<RowData> rows = new ArrayList<>();
            RowData rowData = new RowData();
            rowData.setValues(List.of(cellData));
            rows.add(rowData);
            
            updateRequest.setRows(rows);
            updateRequest.setFields("userEnteredValue");
            updateRequest.setRange(range);
            
            Request request = new Request();
            request.setUpdateCells(updateRequest);
            requests.add(request);
        }
        
        // Execute batch update
        BatchUpdateSpreadsheetRequest batchRequest = new BatchUpdateSpreadsheetRequest();
        batchRequest.setRequests(requests);
        
        sheetsService.spreadsheets()
            .batchUpdate(spreadsheetId, batchRequest)
            .execute();
            
        logger.info("Updated {} cells successfully", cellUpdates.size());
    }
    
    /**
     * Create ExtendedValue from object, handling different types appropriately
     */
    private ExtendedValue createExtendedValue(Object value) {
        ExtendedValue extendedValue = new ExtendedValue();
        
        if (value == null) {
            return extendedValue;
        } else if (value instanceof Number) {
            extendedValue.setNumberValue(((Number) value).doubleValue());
        } else if (value instanceof String) {
            String str = (String) value;
            if (str.startsWith("=")) {
                // It's a formula
                extendedValue.setFormulaValue(str);
            } else {
                // Try to parse as number first
                try {
                    double num = Double.parseDouble(str);
                    extendedValue.setNumberValue(num);
                } catch (NumberFormatException e) {
                    // Keep as string
                    extendedValue.setStringValue(str);
                }
            }
        } else {
            extendedValue.setStringValue(value.toString());
        }
        
        return extendedValue;
    }
    
    /**
     * Parse cell reference like "A5" to [row, column] indices (0-based)
     */
    private int[] parseCellReference(String cellRef) {
        // Extract column letter(s) and row number
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([A-Z]+)(\\d+)").matcher(cellRef.toUpperCase());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid cell reference: " + cellRef);
        }
        
        String columnLetters = matcher.group(1);
        int rowNumber = Integer.parseInt(matcher.group(2));
        
        // Convert column letters to index (A=0, B=1, ..., Z=25, AA=26, etc.)
        int columnIndex = 0;
        for (int i = 0; i < columnLetters.length(); i++) {
            columnIndex = columnIndex * 26 + (columnLetters.charAt(i) - 'A' + 1);
        }
        columnIndex--; // Convert to 0-based
        
        return new int[]{rowNumber - 1, columnIndex}; // Row is 1-based in Sheets, convert to 0-based
    }

    /**
     * Apply formatting to cells
     */
    public void applyFormatting(String spreadsheetId, String sheetName, List<FormatRequest> formatRequests) throws IOException {
        if (formatRequests == null || formatRequests.isEmpty()) {
            return;
        }

        logger.info("Applying formatting to {} cell ranges in sheet: {}", formatRequests.size(), sheetName);
        
        List<Request> requests = new ArrayList<>();
        
        for (FormatRequest formatRequest : formatRequests) {
            // Get sheet ID
            int sheetId = getSheetId(spreadsheetId, sheetName);
            
            // Create format request
            Request request = createFormatRequest(sheetId, formatRequest);
            if (request != null) {
                requests.add(request);
            }
        }

        if (!requests.isEmpty()) {
            BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest()
                    .setRequests(requests);

            sheetsService.spreadsheets()
                    .batchUpdate(spreadsheetId, body)
                    .execute();

            logger.info("Applied formatting to {} cell ranges", requests.size());
        }
    }

    /**
     * Apply formatting based on GoogleSheetsRow formatting flags
     * Only formats rows that have formatting flags set
     */
    private void applyFormatting(String spreadsheetId, String sheetName, List<GoogleSheetsRow> rows, int startRow) throws IOException {
        List<FormatRequest> formatRequests = new ArrayList<>();
        
        for (int i = 0; i < rows.size(); i++) {
            GoogleSheetsRow row = rows.get(i);
            int rowNumber = startRow + i;
            
            // Only create format request if row has formatting flags set
            // Skip rows that don't need formatting
            if (!row.isBold() && !row.isItalic() && !row.isStrikethrough()) {
                continue;
            }
            
            // Apply formatting to columns A-I and R (skip formula columns J-Q)
            // Columns A-I
            formatRequests.add(new FormatRequest(
                rowNumber,                                      // startRow
                rowNumber,                                      // endRow
                1,                                              // startColumn (A)
                9,                                              // endColumn (I)
                row.isBold(),                                   // bold
                row.isItalic(),                                 // italic
                row.isStrikethrough()                           // strikethrough
            ));
            
            // Column R separately
            formatRequests.add(new FormatRequest(
                rowNumber,                                      // startRow
                rowNumber,                                      // endRow
                18,                                             // startColumn (R)
                18,                                             // endColumn (R)
                row.isBold(),                                   // bold
                row.isItalic(),                                 // italic
                row.isStrikethrough()                           // strikethrough
            ));
        }
        
        if (!formatRequests.isEmpty()) {
            applyFormatting(spreadsheetId, sheetName, formatRequests);
        }
    }

    /**
     * Duplicate a sheet (create a backup tab) using the Sheets API.
     * Returns the new sheetId on success, or -1 if sheetsService is null (no-op for tests).
     */
    public int duplicateSheet(String spreadsheetId, String sourceSheetName, String newSheetName, Integer insertSheetIndex) throws IOException {
        if (sheetsService == null) {
            logger.info("Sheets service not configured - skipping duplicateSheet for: {}", sourceSheetName);
            return -1;
        }

        // Resolve source sheet id by name
        int sourceSheetId = getSheetId(spreadsheetId, sourceSheetName);

        DuplicateSheetRequest dupReq = new DuplicateSheetRequest()
                .setSourceSheetId(sourceSheetId)
                .setNewSheetName(newSheetName);
        if (insertSheetIndex != null) dupReq.setInsertSheetIndex(insertSheetIndex);

        Request req = new Request().setDuplicateSheet(dupReq);
        BatchUpdateSpreadsheetRequest body = new BatchUpdateSpreadsheetRequest().setRequests(List.of(req));

        // Execute with retry for rate limits
        BatchUpdateSpreadsheetResponse resp = executeWithRetry("duplicateSheet", () -> {
            return sheetsService.spreadsheets()
                    .batchUpdate(spreadsheetId, body)
                    .execute();
        });

        if (resp.getReplies() == null || resp.getReplies().isEmpty()) {
            throw new IOException("DuplicateSheet failed: no replies from batchUpdate");
        }

        // Defensive extraction of new sheet id
        com.google.api.services.sheets.v4.model.Response reply = resp.getReplies().get(0);
        if (reply == null || reply.getDuplicateSheet() == null || reply.getDuplicateSheet().getProperties() == null) {
            throw new IOException("DuplicateSheet failed: missing duplicate sheet info in reply");
        }

        Integer newSheetId = reply.getDuplicateSheet().getProperties().getSheetId();
        if (newSheetId == null) throw new IOException("DuplicateSheet failed: new sheet id is null");

        logger.info("Duplicated sheet '{}' -> '{}' (new id={})", sourceSheetName, newSheetName, newSheetId);
        return newSheetId;
    }

    /**
     * Get the ID of a sheet by name
     */
    private int getSheetId(String spreadsheetId, String sheetName) throws IOException {
        // Use executeWithRetry to handle rate limits
        Spreadsheet spreadsheet = executeWithRetry("getSheetId", () -> {
            return sheetsService.spreadsheets()
                    .get(spreadsheetId)
                    .setFields("sheets.properties")
                    .execute();
        });

        for (Sheet sheet : spreadsheet.getSheets()) {
            if (sheetName.equals(sheet.getProperties().getTitle())) {
                return sheet.getProperties().getSheetId();
            }
        }

        throw new IOException("Sheet not found: " + sheetName);
    }

    /**
     * Create a format request based on the format request type
     */
    private Request createFormatRequest(int sheetId, FormatRequest formatRequest) {
        CellFormat cellFormat = new CellFormat();
        
        // Set text formatting
        if (formatRequest.isBold() || formatRequest.isItalic() || formatRequest.isStrikethrough()) {
            TextFormat textFormat = new TextFormat();
            if (formatRequest.isBold()) textFormat.setBold(true);
            if (formatRequest.isItalic()) textFormat.setItalic(true);
            if (formatRequest.isStrikethrough()) textFormat.setStrikethrough(true);
            cellFormat.setTextFormat(textFormat);
        }

        // Create the request
        return new Request()
                .setRepeatCell(new RepeatCellRequest()
                        .setRange(new GridRange()
                                .setSheetId(sheetId)
                                .setStartRowIndex(formatRequest.getStartRow() - 1) // Convert to 0-based
                                .setEndRowIndex(formatRequest.getEndRow())
                                .setStartColumnIndex(formatRequest.getStartColumn() - 1) // Convert to 0-based
                                .setEndColumnIndex(formatRequest.getEndColumn()))
                        .setCell(new CellData().setUserEnteredFormat(cellFormat))
                        .setFields("userEnteredFormat.textFormat"));
    }

    /**
     * Parse a row from Google Sheets data
     */
    private GoogleSheetsRow parseRowWithFormatting(List<Object> rowData, int sheetRowNumber, List<CellData> cellDataList) {
        if (rowData == null || rowData.isEmpty()) {
            // Return empty row instead of null to preserve row structure
            GoogleSheetsRow emptyRow = new GoogleSheetsRow();
            emptyRow.setRowNumber(sheetRowNumber);
            emptyRow.setValid(true);
            return emptyRow;
        }

        GoogleSheetsRow row = new GoogleSheetsRow();
        row.setRowNumber(sheetRowNumber);

        try {
            // Parse basic fields (columns A-I)
            if (rowData.size() > 0) row.setLabel(getStringValue(rowData.get(0)));
            if (rowData.size() > 1) row.setDomain(getStringValue(rowData.get(1)));
            if (rowData.size() > 2) row.setOpenTime(parseDate(getStringValue(rowData.get(2))));
            if (rowData.size() > 3) row.setOpenPrice(parseDecimal(getStringValue(rowData.get(3))));
            if (rowData.size() > 4) row.setNumberOfStock(parseDecimal(getStringValue(rowData.get(4))));
            if (rowData.size() > 5) row.setOpenFeeTax(parseDecimal(getStringValue(rowData.get(5))));
            if (rowData.size() > 6) row.setCloseTime(parseDate(getStringValue(rowData.get(6))));
            if (rowData.size() > 7) row.setClosePrice(parseDecimal(getStringValue(rowData.get(7))));
            if (rowData.size() > 8) row.setCloseFeeTax(parseDecimal(getStringValue(rowData.get(8))));

            // Parse formula columns (columns J-Q, indices 9-16)
            List<String> formulaColumns = new ArrayList<>();
            for (int i = 9; i <= 16; i++) {
                if (cellDataList != null && i < cellDataList.size()) {
                    // prefer formulaValue if present
                    String formulaValue = getCellUserValue(cellDataList.get(i));
                    formulaColumns.add(formulaValue);
                } else if (i < rowData.size()) {
                    formulaColumns.add(getStringValue(rowData.get(i)));
                } else {
                    formulaColumns.add("");
                }
            }
            row.setFormulaColumns(formulaColumns);

            // Parse remaining fields (columns R-T, indices 17-19)
            if (rowData.size() > 17) row.setDescription(getStringValue(rowData.get(17)));
            if (rowData.size() > 18) row.setRegion(getStringValue(rowData.get(18)));
            if (rowData.size() > 19) row.setReserved(getStringValue(rowData.get(19)));

            // Determine formatting from cellDataList (if available)
            boolean bold = false;
            boolean italic = false;
            boolean strikethrough = false;
            if (cellDataList != null && !cellDataList.isEmpty()) {
                // Check all cells for formatting
                for (CellData cd : cellDataList) {
                    if (cd == null || cd.getUserEnteredFormat() == null || cd.getUserEnteredFormat().getTextFormat() == null) continue;
                    TextFormat tf = cd.getUserEnteredFormat().getTextFormat();
                    if (tf.getBold() != null && tf.getBold()) bold = true;
                    if (tf.getItalic() != null && tf.getItalic()) italic = true;
                    if (tf.getStrikethrough() != null && tf.getStrikethrough()) strikethrough = true;
                }
            }

            row.setBold(bold);
            row.setItalic(italic);
            row.setStrikethrough(strikethrough);

            // Determine validity according to Requirement.md:
            // Strikethrough = closed position = invalid
            // Bold+Italic without strikethrough = open position = valid  
            // Note: Some open positions may have bold+italic formatting
            row.setValid(!strikethrough);

        } catch (Exception e) {
            logger.error("Error parsing row {}: {}", sheetRowNumber, e.getMessage());
            // Return row with whatever data we could parse
            return row;
        }

        return row;
    }

    /**
     * Copy a row preserving all data including formulas
     */
    public GoogleSheetsRow copyRowWithFormulas(GoogleSheetsRow sourceRow) {
        if (sourceRow == null) return null;
        
        GoogleSheetsRow newRow = new GoogleSheetsRow();
        
        // Copy all basic fields
        newRow.setLabel(sourceRow.getLabel());
        newRow.setDomain(sourceRow.getDomain());
        newRow.setOpenTime(sourceRow.getOpenTime());
        newRow.setOpenPrice(sourceRow.getOpenPrice());
        newRow.setNumberOfStock(sourceRow.getNumberOfStock());
        newRow.setOpenFeeTax(sourceRow.getOpenFeeTax());
        newRow.setCloseTime(sourceRow.getCloseTime());
        newRow.setClosePrice(sourceRow.getClosePrice());
        newRow.setCloseFeeTax(sourceRow.getCloseFeeTax());
        
        // Copy formula columns
        if (sourceRow.getFormulaColumns() != null) {
            newRow.setFormulaColumns(new ArrayList<>(sourceRow.getFormulaColumns()));
        }
        
        // Copy remaining fields
        newRow.setDescription(sourceRow.getDescription());
        newRow.setRegion(sourceRow.getRegion());
        newRow.setReserved(sourceRow.getReserved());
        
        // Copy formatting
        newRow.setBold(sourceRow.isBold());
        newRow.setItalic(sourceRow.isItalic());
        newRow.setStrikethrough(sourceRow.isStrikethrough());
        newRow.setValid(sourceRow.isValid());
        
        return newRow;
    }

    private String getStringValueFromCell(CellData cd) {
        if (cd == null) return "";
        if (cd.getFormattedValue() != null) return cd.getFormattedValue();
        return cd.getEffectiveValue() != null ? cd.getEffectiveValue().toString() : "";
    }

    private String getCellUserValue(CellData cd) {
        if (cd == null) return "";
        
        // Try userEnteredValue first
        if (cd.getUserEnteredValue() != null) {
            try {
                // Check for formula
                Object formula = cd.getUserEnteredValue().get("formulaValue");
                if (formula != null) return formula.toString();
                
                // Check for string value
                String stringValue = cd.getUserEnteredValue().getStringValue();
                if (stringValue != null) return stringValue;
                
                // Check for number value
                Double numberValue = cd.getUserEnteredValue().getNumberValue();
                if (numberValue != null) return numberValue.toString();
            } catch (Exception e) {
                logger.debug("Error extracting user entered value: {}", e.getMessage());
                // Fallbacks below
            }
        }

        // Try effective value for formulas that have been evaluated
        if (cd.getEffectiveValue() != null) {
            // Extract proper type from effective value
            String stringValue = cd.getEffectiveValue().getStringValue();
            if (stringValue != null && !stringValue.isEmpty()) {
                return stringValue;
            }
            
            Double numberValue = cd.getEffectiveValue().getNumberValue();
            if (numberValue != null) {
                return numberValue.toString();
            }
            
            // Last resort: toString() but this might give JSON
            String effectiveValue = cd.getEffectiveValue().toString();
            if (effectiveValue != null && !effectiveValue.isEmpty() && !effectiveValue.startsWith("{")) {
                return effectiveValue;
            }
        }
        
        // Fallback to formatted value (what the user sees)
        if (cd.getFormattedValue() != null) {
            String formatted = cd.getFormattedValue();
            logger.debug("Using formatted value: {}", formatted);
            return formatted;
        }
        
        return "";
    }

    /**
     * Helper classes for cell updates and formatting
     */
    public static class CellUpdate {
        private final String cellRange;
        private final Object value;

        public CellUpdate(String cellRange, Object value) {
            this.cellRange = cellRange;
            this.value = value;
        }

        public String getCellRange() { return cellRange; }
        public Object getValue() { return value; }
    }

    public static class FormatRequest {
        private final int startRow;
        private final int endRow;
        private final int startColumn;
        private final int endColumn;
        private final boolean bold;
        private final boolean italic;
        private final boolean strikethrough;

        public FormatRequest(int startRow, int endRow, int startColumn, int endColumn,
                           boolean bold, boolean italic, boolean strikethrough) {
            this.startRow = startRow;
            this.endRow = endRow;
            this.startColumn = startColumn;
            this.endColumn = endColumn;
            this.bold = bold;
            this.italic = italic;
            this.strikethrough = strikethrough;
        }

        public int getStartRow() { return startRow; }
        public int getEndRow() { return endRow; }
        public int getStartColumn() { return startColumn; }
        public int getEndColumn() { return endColumn; }
        public boolean isBold() { return bold; }
        public boolean isItalic() { return italic; }
        public boolean isStrikethrough() { return strikethrough; }
    }

    // Helper methods for parsing
    private String getStringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    private java.math.BigDecimal parseDecimal(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        
        String trimmedValue = value.trim();
        
        // Skip formulas - they should be handled by the sheet's calculated values
        if (trimmedValue.startsWith("=")) {
            logger.debug("Skipping formula value: {}", trimmedValue);
            return null;
        }
        
        try {
            return new java.math.BigDecimal(trimmedValue);
        } catch (NumberFormatException e) {
            logger.warn("Unable to parse decimal: {}", value);
            return null;
        }
    }

    private java.time.LocalDate parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        String trimmedValue = value.trim();

        // First try parsing as string date format
        try {
            return java.time.LocalDate.parse(trimmedValue);
        } catch (Exception e) {
            // Try numeric conversion
        }

        // Try parsing as numeric date if string parsing failed
        try {
            double numValue = Double.parseDouble(trimmedValue);
            return convertNumericToDate(numValue);
        } catch (NumberFormatException e) {
            // Not a number either
        }

        logger.warn("Unable to parse date: {}", value);
        return null;
    }

    /**
     * Convert numeric value to LocalDate
     * Supports Excel serial dates, Unix timestamps (seconds and milliseconds)
     *
     * @param numValue numeric value representing a date
     * @return LocalDate if recognized as date, null if it's a plain number
     */
    private java.time.LocalDate convertNumericToDate(double numValue) {
        // Excel serial date range: ~1-60000 represents approximately 1900-2063
        if (numValue >= 1 && numValue <= 60000) {
            try {
                long daysSinceEpoch = Math.round(numValue);

                // Excel has a leap year bug: it considers 1900 as a leap year (it's not)
                // So we adjust for dates after Feb 28, 1900
                if (daysSinceEpoch >= 60) {
                    daysSinceEpoch--;
                }

                java.time.LocalDate excelEpoch = java.time.LocalDate.of(1900, 1, 1);
                java.time.LocalDate result = excelEpoch.plusDays(daysSinceEpoch - 1);
                logger.debug("Excel serial date {} converted to: {}", numValue, result);
                return result;
            } catch (Exception e) {
                logger.warn("Failed to convert Excel serial date {}: {}", numValue, e.getMessage());
            }
        }

        // Unix timestamp (milliseconds): approximately 1000000000000 to 2000000000000
        else if (numValue >= 1000000000000L && numValue <= 2000000000000L) {
            try {
                long milliseconds = Math.round(numValue);
                return java.time.Instant.ofEpochMilli(milliseconds)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();
            } catch (Exception e) {
                logger.warn("Failed to convert Unix timestamp (ms) {}: {}", numValue, e.getMessage());
            }
        }

        // Unix timestamp (seconds): approximately 1000000000 to 2000000000
        else if (numValue >= 1000000000 && numValue < 1000000000000L) {
            try {
                long seconds = Math.round(numValue);
                return java.time.Instant.ofEpochSecond(seconds)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();
            } catch (Exception e) {
                logger.warn("Failed to convert Unix timestamp (sec) {}: {}", numValue, e.getMessage());
            }
        }

        // Plain number like year (1900-2100) - NOT a date
        else if (numValue >= 1900 && numValue <= 2100) {
            logger.debug("Value {} is a year/plain number, not a date", numValue);
            return null;
        }

        logger.debug("Numeric value {} doesn't match any known date format", numValue);
        return null;
    }

    private boolean isHeaderRow(List<Object> rowData) {
        if (rowData == null || rowData.isEmpty()) {
            return false;
        }
        
        // Check if this looks like a header row
        String firstCell = getStringValue(rowData.get(0)).toLowerCase();
        return firstCell.contains("label") || firstCell.contains("名称");
    }
    
    /**
     * Get the underlying Sheets service instance
     */
    public Sheets getSheetsService() {
        return sheetsService;
    }
    
    /**
     * Get sheet ID by name
     */
    public Integer getSheetIdByName(String spreadsheetId, String sheetName) throws IOException {
        Spreadsheet spreadsheet = sheetsService.spreadsheets().get(spreadsheetId).execute();
        if (spreadsheet.getSheets() != null) {
            for (Sheet sheet : spreadsheet.getSheets()) {
                if (sheet.getProperties() != null && 
                    sheetName.equals(sheet.getProperties().getTitle())) {
                    return sheet.getProperties().getSheetId();
                }
            }
        }
        return null;
    }
    
    /**
     * Package-private method for testing the retry mechanism
     */
    <T> T executeWithRetryForTest(String operationName, Callable<T> apiCall) throws IOException {
        return executeWithRetry(operationName, apiCall);
    }
    
    /**
     * Check if a row is empty (no meaningful data)
     * Used to filter out empty rows during read operations to improve performance
     */
    private boolean isRowEmpty(GoogleSheetsRow row) {
        if (row == null) return true;
        
        // Check if basic fields are empty
        boolean hasLabel = row.getLabel() != null && !row.getLabel().trim().isEmpty();
        boolean hasData = row.getOpenTime() != null || 
                         row.getOpenPrice() != null || 
                         row.getNumberOfStock() != null ||
                         row.getCloseTime() != null ||
                         row.getClosePrice() != null ||
                         (row.getDescription() != null && !row.getDescription().trim().isEmpty());
        
        return !hasLabel && !hasData;
    }
}